#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重建 knowledge_chunk 的关键词索引。

背景：knowledge_chunk 已从「切块文本表」改造为「关键词索引表」——
  每个关键词单独一行、单独向量化（base_id 指向 knowledge_base）。
  ALTER 只做了列名变更，存量行的 keyword 列里仍是旧的「提炼语义」文本，
  向量也不是纯关键词向量，必须全量重建，否则检索命中质量很差。

逻辑与后端保持一致（xsl.sai.client.service.impl.KnowledgeBaseServiceImpl#writeChunks
 / xsl.sai.agent.tool.KnowledgeBaseTool#writeChunks / SemanticRefineHandler）：
  1. content <= 1000 字符        -> 直接整段提炼关键词
     content >  1000 字符        -> 先用 LLM 语义分块（<<<CHUNK>>> 分隔），逐块提炼后合并去重
  2. 关键词为空且向量可用       -> 兜底用正文硬截断前 4000 字符作为单个关键词
  3. 每个关键词单独 embed（bge-m3, 1024 维）后写入 knowledge_chunk

关键词提炼用 OpenAI 兼容 chat 端点（默认 DeepSeek，与 application-agent.yml 一致）；
向量化用本地 Ollama bge-m3（与 EmbeddingClient format=ollama 一致）。

依赖：pip install pymysql requests

用法：
  # 演练（不读库、不写库，验证模型链路是否通）
  python3 rebuild_kb_keywords.py --dry-run --text "阳光信保成立于2008年，主营信用保证保险。"

  # 清空 knowledge_chunk 后全量重建（推荐，即「清理后重建」）
  python3 rebuild_kb_keywords.py --truncate --yes

  # 只重建某几条 / 不清空 / 只看不动库
  python3 rebuild_kb_keywords.py --only 12 --only 13
  python3 rebuild_kb_keywords.py --no-truncate --limit 10
  python3 rebuild_kb_keywords.py --dry-run

环境变量：
  SAI_DB_PASSWORD   数据库密码（默认 ***REMOVED***，可用 --db-password 覆盖）
  SAI_CHAT_API_KEY  关键词提炼的 chat key；缺省时自动从
                    sai-agent/src/main/resources/application-agent.yml 读取（本地文件，不落凭证）
"""

import argparse
import json
import os
import re
import sys
import time

import pymysql
import requests

# ===================== 与 Java 常量对齐 =====================
SEGMENT_MIN_CHARS = 1000      # SemanticRefineHandler.SEGMENT_MIN_CHARS
DEFAULT_CHUNK_SIZE = 200      # SemanticRefineHandler.DEFAULT_CHUNK_SIZE
SAFE_EMBED_CHARS = 4000       # SemanticRefineHandler.SAFE_EMBED_CHARS
CHUNK_DELIMITER = "<<<CHUNK>>>"

KEYWORD_SYSTEM = """你是一个关键词提取专家。请从用户提供的文本中提取用于向量检索的核心关键词。
要求：
1. 提取 3~10 个最能代表文本主题的词或短语，优先保留专有名词、实体、术语、方法名与关键数字；
2. 每个关键词单独占一行，不要编号、不要解释、不要任何额外符号或标点；
3. 若原文极短或无明显主题，不输出任何内容（直接返回空）。"""

KEYWORD_USER = """请从下面的文本中提取关键词（每个关键词单独一行，不要编号与解释）：

--- 原文 ---
%s
--- 原文结束 ---"""

SEGMENT_SYSTEM = """你是一个文档语义分块专家。请将用户提供的长文本切分为若干个「语义完整、自包含」的块（chunk）。
要求：
1. 严格按【语义/话题/段落】边界切分，绝不把一个完整句子、一个条款、一个步骤从中间切断；
2. 每个块尽量围绕一个独立主题，长度适中（目标约 %d 个 token，但不必精确，宁可保持语义完整）；
3. 块与块之间用唯一分隔符 "%s" 隔开，每个块内部不得再出现该分隔符；
4. 保留原文全部内容，不得删改、不得概括、不得添加序号或任何说明文字；
5. 若原文本身很短（不足一个块），则整体作为唯一一块原样返回。"""

SEGMENT_USER = """请按上述规则对下面的文本做语义分块（块间用 %s 分隔）：

--- 原文 ---
%s
--- 原文结束 ---"""

# ===================== 默认配置 =====================
DB = dict(host="127.0.0.1", port=3306, user="root", password="***REMOVED***",
          database="sai", charset="utf8mb4")
OLLAMA_EMBED_URL = "http://127.0.0.1:11434/api/embed"
EMBED_MODEL = "bge-m3"
EMBED_BATCH = 32
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT_YML = os.path.join(PROJECT_ROOT, "sai-agent/src/main/resources/application-agent.yml")


# ===================== 工具 =====================
def parse_keyword_lines(raw):
    """对齐 SemanticRefineHandler#parseKeywordLines：按行拆分、去列表前缀、去重、去空"""
    out, seen = [], set()
    for line in (raw or "").splitlines():
        k = line.strip()
        k = re.sub(r"^([-*•]|\d+[.、)])\s+", "", k).strip()
        if not k or k in seen:
            continue
        seen.add(k)
        out.append(k)
    return out


def safe_clamp(s):
    """对齐 SemanticRefineHandler#safeClamp"""
    if s is None:
        return None
    return s if len(s) <= SAFE_EMBED_CHARS else s[:SAFE_EMBED_CHARS]


def dedup(seq):
    return list(dict.fromkeys(seq))


# ===================== 模型调用 =====================
class ChatClient:
    """OpenAI 兼容 chat 端点（DeepSeek 默认），用于关键词提炼 + 语义分块"""

    def __init__(self, base_url, model, api_key, backend="openai", timeout=60):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.backend = backend
        self.timeout = timeout

    def chat(self, system, user):
        if self.backend == "ollama":
            url = self.base_url if self.base_url.endswith("/api/chat") else self.base_url + "/api/chat"
            payload = {"model": self.model, "stream": False,
                       "messages": [{"role": "system", "content": system},
                                    {"role": "user", "content": user}]}
            headers = {"Content-Type": "application/json"}
        else:
            url = self.base_url if self.base_url.endswith("/chat/completions") \
                else self.base_url + "/chat/completions"
            payload = {"model": self.model, "temperature": 0.2,
                       "messages": [{"role": "system", "content": system},
                                    {"role": "user", "content": user}]}
            headers = {"Content-Type": "application/json",
                       "Authorization": "Bearer " + (self.api_key or "")}
        r = requests.post(url, json=payload, headers=headers, timeout=self.timeout)
        r.raise_for_status()
        d = r.json()
        if self.backend == "ollama":
            return (d.get("message") or {}).get("content", "") or ""
        return ((d.get("choices") or [{}])[0].get("message") or {}).get("content", "") or ""


def extract_keywords(chat, text):
    """对齐 SemanticRefineHandler#extractKeywords（强制模式）"""
    if not text or not text.strip():
        return []
    try:
        raw = chat.chat(KEYWORD_SYSTEM, KEYWORD_USER % text)
    except Exception as e:
        print(f"    [warn] 关键词提炼失败: {e}", file=sys.stderr)
        return []
    return parse_keyword_lines((raw or "").strip())


def segment(chat, text, chunk_size=DEFAULT_CHUNK_SIZE):
    """对齐 SemanticRefineHandler#segment：仅长文本走模型分块，短文本整体一块"""
    if not text or not text.strip():
        return []
    if len(text) <= SEGMENT_MIN_CHARS:
        return [text]
    try:
        raw = chat.chat(SEGMENT_SYSTEM % (chunk_size, CHUNK_DELIMITER),
                        SEGMENT_USER % (CHUNK_DELIMITER, text))
    except Exception as e:
        print(f"    [warn] 语义分块失败，回落整体一块: {e}", file=sys.stderr)
        return [text]
    parts = [p.strip() for p in (raw or "").split(CHUNK_DELIMITER)]
    parts = [p for p in parts if p]
    return parts or [text]


def build_keywords(chat, content):
    """对齐 writeChunks：长文本先分块再逐块提炼，合并去重"""
    if not content or not content.strip():
        return []
    blocks = segment(chat, content)
    kws = []
    for b in blocks:
        kws.extend(extract_keywords(chat, b))
    return dedup(kws)


def embed_texts(texts):
    """Ollama 批量向量接口，返回 List[List[float]]，顺序与输入一致"""
    if not texts:
        return []
    r = requests.post(OLLAMA_EMBED_URL,
                      json={"model": EMBED_MODEL, "input": texts}, timeout=60)
    r.raise_for_status()
    return r.json()["embeddings"]


def to_vec_json(vec):
    return "[" + ",".join(repr(float(x)) for x in vec) + "]"


def ollama_alive():
    try:
        r = requests.get(OLLAMA_EMBED_URL.replace("/api/embed", "/api/tags"), timeout=5)
        return r.status_code == 200
    except Exception:
        return False


# ===================== 配置读取 =====================
def load_agent_chat_cfg():
    """从 application-agent.yml 读取 chat 端点配置（本地文件，不落凭证到脚本）"""
    cfg = {"base": "https://api.deepseek.com", "model": None, "key": None}
    try:
        with open(AGENT_YML, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith("modelName:"):
                    cfg["model"] = s.split(":", 1)[1].strip()
                elif s.startswith("apiKey:"):
                    cfg["key"] = s.split(":", 1)[1].strip()
                elif s.startswith("apiBase:"):
                    cfg["base"] = s.split(":", 1)[1].strip()
    except Exception as e:
        print(f"[warn] 读取 {AGENT_YML} 失败: {e}", file=sys.stderr)
    return cfg


# ===================== 主流程 =====================
def main():
    ap = argparse.ArgumentParser(description="清理并重建 knowledge_chunk 关键词索引")
    ap.add_argument("--truncate", action="store_true",
                    help="重建前先清空 knowledge_chunk（默认即为「清理后重建」语义，见 --no-truncate）")
    ap.add_argument("--no-truncate", action="store_true", help="不清空，直接追加/覆盖重建")
    ap.add_argument("--yes", action="store_true", help="确认执行写库操作")
    ap.add_argument("--dry-run", action="store_true", help="只演练，不写库")
    ap.add_argument("--text", help="演练模式：直接对这段文本提炼关键词（不读库）")
    ap.add_argument("--only", type=int, action="append", default=[], help="只重建指定 knowledge_base.id，可重复")
    ap.add_argument("--limit", type=int, default=0, help="最多处理条数，0=不限")
    ap.add_argument("--chunk-size", type=int, default=DEFAULT_CHUNK_SIZE, help="分块粒度提示(token)")
    ap.add_argument("--db-password", default=os.environ.get("SAI_DB_PASSWORD", DB["password"]))
    ap.add_argument("--chat-backend", default="openai", choices=["openai", "ollama"])
    ap.add_argument("--chat-url", default=None, help="chat 端点，默认取自 application-agent.yml")
    ap.add_argument("--chat-model", default=None)
    ap.add_argument("--chat-key", default=os.environ.get("SAI_CHAT_API_KEY"))
    args = ap.parse_args()

    cfg = load_agent_chat_cfg()
    chat = ChatClient(base_url=args.chat_url or cfg["base"],
                      model=args.chat_model or cfg["model"] or "deepseek-chat",
                      api_key=args.chat_key or cfg["key"],
                      backend=args.chat_backend)
    print(f"chat  : {chat.backend} {chat.base_url} model={chat.model}")
    print(f"embed : {OLLAMA_EMBED_URL} model={EMBED_MODEL}")

    embedding_on = ollama_alive()
    print(f"Ollama: {'可用' if embedding_on else '不可用（关键词将无向量）'}")

    # ---- 演练：只跑一段文本 ----
    if args.text:
        kws = build_keywords(chat, args.text)
        print(f"\n[dry-run] 提炼关键词 {len(kws)} 个: {kws}")
        if embedding_on and kws:
            vecs = embed_texts(kws)
            print(f"[dry-run] 向量维度: {len(vecs[0])}，示例首 3 维: {vecs[0][:3]}")
        elif not kws:
            fb = safe_clamp(args.text)
            print(f"[dry-run] 关键词为空，兜底将写入截断正文（{len(fb or '')} 字符）")
        return

    truncate = args.truncate and not args.no_truncate
    if truncate and not args.yes and not args.dry_run:
        print("\n⚠️  --truncate 会清空 knowledge_chunk 全表；确认请加 --yes（或先跑 --dry-run）")
        sys.exit(1)

    conn = pymysql.connect(**{**DB, "password": args.db_password})
    try:
        with conn.cursor() as cur:
            if truncate:
                if args.dry_run:
                    cur.execute("SELECT COUNT(*) FROM knowledge_chunk")
                    print(f"[dry-run] 将清空 knowledge_chunk（当前 {cur.fetchone()[0]} 行）")
                else:
                    cur.execute("TRUNCATE TABLE knowledge_chunk")
                    conn.commit()
                    print("已清空 knowledge_chunk")

            where, params = "WHERE is_delete = 0", []
            if args.only:
                where += " AND id IN (%s)" % ",".join(["%s"] * len(args.only))
                params += args.only
            if args.limit > 0:
                where += " LIMIT %s"
                params.append(args.limit)
            cur.execute(f"SELECT id, content FROM knowledge_base {where}", params)
            rows = cur.fetchall()
        print(f"待重建条目数: {len(rows)}")
        if not rows:
            print("无数据，无需重建。")
            return

        total_kw = 0
        for idx, (bid, content) in enumerate(rows, 1):
            content = (content or "").strip()
            if not content:
                print(f"[{idx}/{len(rows)}] #{bid} 正文为空，跳过")
                continue
            kws = build_keywords(chat, content)
            if not kws and embedding_on:
                fb = safe_clamp(content)
                kws = [fb] if fb else []
                print(f"[{idx}/{len(rows)}] #{bid} 关键词为空，用截断正文兜底")
            print(f"[{idx}/{len(rows)}] #{bid} 关键词 {len(kws)} 个: {kws[:8]}{' ...' if len(kws) > 8 else ''}")
            if not kws:
                continue

            if args.dry_run:
                total_kw += len(kws)
                continue

            # 逐个关键词向量化（分批）
            vecs = []
            for i in range(0, len(kws), EMBED_BATCH):
                part = kws[i:i + EMBED_BATCH]
                try:
                    vecs.extend(embed_texts(part))
                except Exception as e:
                    print(f"    向量化失败，该批无向量写入: {e}", file=sys.stderr)
                    vecs.extend([None] * len(part))
                time.sleep(0.05)

            with conn.cursor() as cur:
                for kw, vec in zip(kws, vecs):
                    if vec is None:
                        cur.execute("INSERT INTO knowledge_chunk (base_id, keyword, is_delete) "
                                    "VALUES (%s, %s, 0)", (bid, kw))
                    else:
                        cur.execute("INSERT INTO knowledge_chunk (base_id, keyword, embedding_vec, is_delete) "
                                    "VALUES (%s, %s, STRING_TO_VECTOR(%s), 0)",
                                    (bid, kw, to_vec_json(vec)))
                conn.commit()
            total_kw += len(kws)

        if args.dry_run:
            print(f"\n[dry-run] 完成：将写入 {total_kw} 个关键词行（未改动数据库）")
        else:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM knowledge_chunk WHERE is_delete = 0")
                n = cur.fetchone()[0]
            print(f"\n完成：写入 {total_kw} 个关键词行，knowledge_chunk 现有 {n} 行")
    finally:
        try:
            conn.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
