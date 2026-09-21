package xsl.sai.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.agent.handler.SemanticRefineHandler;
import xsl.sai.framework.client.EmbeddingClient;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.framework.result.VectorRecord;
import xsl.sai.framework.util.VectorUtils;

import java.time.Duration;
import java.util.*;

/**
 * 知识库工具 —— 供 Agent 沉淀与检索知识库（对应前端「知识库」页面维护的 knowledge_base 表）。
 *
 * <p>写入：content 经 {@link SemanticRefineHandler#extractKeywords} 提炼关键词（超长文本先语义分块再逐块提炼、合并去重），
 * 每个关键词单独向量化后落入 knowledge_chunk 表（base_id, keyword, embedding_vec），作为知识库语义检索入口。
 * 检索：{@code query_knowledge} 经 knowledge_chunk.keyword 向量召回，再回填对应的 knowledge_base 条目正文返回给 Agent。
 *
 * <p><b>纯流式、零阻塞</b>：返回 {@link Mono<String>}，底层 R2DBC 响应式驱动，不再使用 {@code .block()}。
 *
 * @author SAI
 */
@Slf4j
@Component
public class KnowledgeBaseTool {

    private static final int DEFAULT_CHUNK_SIZE = 200;
    /** 超长文本阈值：超过该字符数先语义分块再逐块提炼关键词（与 SemanticRefineHandler.SEGMENT_MIN_CHARS 对齐） */
    private static final int SEGMENT_MIN_CHARS = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** 批量写入（多条 VALUES）的超时：单条 SQL 携带多个向量，比单条写入更耗时 */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 检索默认相似度阈值（COSINE，越大越相关）。
     * 关键词索引下查询句是自然语言长句、被召回的是短关键词，余弦分天然偏低
     * （实测最相关命中约 0.73），沿用 0.8 会过滤掉几乎所有结果，故默认取 0.6。
     */
    private static final double DEFAULT_THRESHOLD = 0.6;

    private final R2dbcClient r2dbcClient;
    private final EmbeddingClient embeddingClient;
    /** 语义提炼 Handler：知识库切块写入前先提炼为精简语义，再向量化存储 */
    private final SemanticRefineHandler semanticRefineHandler;

    public KnowledgeBaseTool(R2dbcClient r2dbcClient,
                             EmbeddingClient embeddingClient,
                             SemanticRefineHandler semanticRefineHandler) {
        this.r2dbcClient = r2dbcClient;
        this.embeddingClient = embeddingClient;
        this.semanticRefineHandler = semanticRefineHandler;
    }

    @Tool(name = "save_knowledge",
            description = "将一条知识写入【知识库 knowledge_base 表】，供 RAG 检索。"
                    + "调用后该知识会经关键词提炼 + 向量化持久化（每个关键词单独向量化，落入 knowledge_chunk），"
                    + "并可在前端「知识库」页面查看与检索。"
                    + "请在完成知识沉淀后调用本工具，使知识入库。"
                    + "参数：content 为必填文本内容；chunkSize 为长文本分块 token 数（可空，默认200）。")
    public Mono<String> saveKnowledge(
            RuntimeContext runtimeContext,
            @ToolParam(name = "content", required = true, description = "知识文本内容") String content,
            @ToolParam(name = "chunkSize", required = false, description = "长文本分块 token 数，默认200") Integer chunkSize) {
        final String rawContent = VectorUtils.blankToNull(content);
        if (rawContent == null) {
            return Mono.just("⛔ 保存失败：content 不能为空。");
        }
        final String ct = rawContent;
        int cs = (chunkSize != null && chunkSize > 0) ? chunkSize : DEFAULT_CHUNK_SIZE;

        // knowledge_base 已移除 name/keyword 字段，直接插入新条目（不再按名称去重）；
        // 用 executeReturnId 取回自增主键，再对 content 做关键词提炼 + 逐关键词向量化写入 knowledge_chunk。
        Map<String, Object> p = new LinkedHashMap<>(2);
        p.put("content", ct);
        return r2dbcClient.executeReturnId(
                        "INSERT INTO knowledge_base (content, is_delete) VALUES (:content, 0)", p)
                .timeout(TIMEOUT)
                .flatMap(id -> {
                    if (id == null || id == 0L) {
                        return Mono.just("⚠️ 已写入但未取到主键，知识库条目可能仍在（请稍后在前端确认）。");
                    }
                    return writeChunks(id, ct, cs)
                            .thenReturn("✅ 已保存到知识库（条目ID=" + id
                                    + "，已提炼关键词并向量化）。该知识现已可被 query_knowledge 与页面语义检索召回。");
                })
                .onErrorResume(e -> {
                    log.warn("save_knowledge 失败: {}", e.getMessage());
                    return Mono.just("保存失败：" + e.getMessage());
                });
    }

    @Tool(name = "query_knowledge",
            description = "检索【知识库 knowledge_base】中的知识（RAG 召回）。"
                    + "内部用本地 embeddings 服务(Ollama 11434, bge-m3) 把查询文本向量化，"
                    + "在 knowledge_chunk 表按 keyword 向量做余弦相似度检索，命中后回填对应的 knowledge_base 条目正文，"
                    + "按相似度降序返回最相关的若干条知识（已按条目去重）。"
                    + "请在需要「从知识库查找某方面知识」时调用，例如用户问到知识库涵盖的规范 / FAQ / 文档内容。"
                    + "参数：query 为查询文本（必填）；top_k 为返回条目数（可空，默认5，最大20）；"
                    + "threshold 为余弦相似度阈值 0~1（可空，默认0.6）。"
                    + "注意：知识库以【关键词】建索引，查询句与关键词的余弦分天然偏低（最相关命中通常只有 0.5~0.75），"
                    + "默认 0.6 即可；如果确实召回不到结果，可下调到 0.3~0.5 再试，不要填 0.8 之类高值。")
    public Mono<String> queryKnowledge(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", required = true, description = "查询文本（自然语言问题或关键词）") String query,
            @ToolParam(name = "top_k", required = false, description = "返回条目数，默认5，最大20") Integer topK,
            @ToolParam(name = "threshold", required = false,
                    description = "余弦相似度阈值 0~1，默认0.6；召回不到时可下调到 0.3~0.5，勿填 0.8 以上") Double threshold) {
        final String q = VectorUtils.blankToNull(query);
        if (q == null) {
            return Mono.just("⛔ 检索失败：query 不能为空。");
        }
        if (!embeddingClient.isEnabled()) {
            return Mono.just("⚠️ Embedding 客户端未启用（agentScope.embedding.enabled=false），无法做语义检索。"
                    + "请先在配置中启用本地 embeddings 服务，或确认 Ollama 11434 可用且已 `ollama pull bge-m3`。");
        }
        int k = (topK == null || topK <= 0) ? 5 : Math.min(topK, 20);
        double th = (threshold == null) ? DEFAULT_THRESHOLD : threshold;
        Map<String, String> meta = new LinkedHashMap<>(1);
        meta.put("base_id", "kc.base_id");
        // 多取关键词命中，再按 base_id 去重，保证返回 k 条知识库条目
        int fetch = Math.max(k * 4, 20);
        return Mono.fromCallable(() -> embeddingClient.embed(q))
                // embed 内部是同步阻塞 HTTP，必须切到 boundedElastic，否则阻塞 Reactor Netty 事件循环
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(qv -> r2dbcClient.vectorSearch(qv, "kc.embedding_vec", "kc.keyword", meta,
                                "knowledge_chunk kc", "kc.is_delete = 0", Collections.emptyMap(), fetch, th, false)
                        .collectList()
                        .map(this::groupByBase))
                .flatMapMany(agg -> Flux.fromIterable(agg.entrySet()))
                .flatMap(entry -> fetchBaseContent(entry.getKey())
                        .map(content -> new KnowledgeHit(entry.getKey(), content, entry.getValue().score, entry.getValue().keyword())))
                .sort((a, b) -> Double.compare(b.score, a.score))
                .take(k)
                .map(this::formatHit)
                .collectList()
                .map(blocks -> {
                    if (blocks.isEmpty()) {
                        return "知识库检索完成，无满足阈值（" + th + "）的结果。";
                    }
                    return "知识库检索返回 " + blocks.size() + " 条（阈值 " + th + "）：\n" + String.join("\n", blocks);
                })
                .onErrorResume(e -> {
                    log.warn("query_knowledge 失败: {}", e.getMessage());
                    return Mono.just("知识库检索失败：" + e.getMessage());
                });
    }

    /** 把关键词命中按 base_id 聚合：保留最高相似度，并收集命中关键词（去重、最多 5 个） */
    private Map<Long, BaseAgg> groupByBase(List<VectorRecord> list) {
        Map<Long, BaseAgg> m = new LinkedHashMap<>();
        for (VectorRecord r : list) {
            Long baseId = VectorUtils.toLong(r.getMetadata() != null ? r.getMetadata().get("base_id") : null);
            if (baseId == null) {
                continue;
            }
            BaseAgg agg = m.computeIfAbsent(baseId, k -> new BaseAgg());
            if (r.getScore() > agg.score) {
                agg.score = r.getScore();
            }
            if (r.getContent() != null) {
                agg.keywords.add(r.getContent());
            }
        }
        return m;
    }

    /** 按 base_id 拉取 knowledge_base 正文（无结果返回空串，保证主链路不中断） */
    private Mono<String> fetchBaseContent(Long baseId) {
        Map<String, Object> p = Collections.singletonMap("id", baseId);
        return r2dbcClient.queryOne(
                        "SELECT content FROM knowledge_base WHERE id = :id AND is_delete = 0", p, Map.class)
                .map(row -> {
                    Object c = row != null ? row.get("content") : null;
                    return c != null ? c.toString() : "";
                })
                .defaultIfEmpty("");
    }

    private String formatHit(KnowledgeHit h) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n[条目#%d] score=%.4f", h.baseId, h.score));
        if (h.keyword != null && !h.keyword.isEmpty()) {
            sb.append(" 命中关键词：").append(h.keyword);
        }
        sb.append("\n").append(h.content == null || h.content.isBlank() ? "（无正文）" : h.content);
        return sb.toString();
    }

    /**
     * 把文本内容提炼为关键词集合（超长文本先语义分块、逐块提炼后合并去重），
     * 再<b>批量向量化</b>（一次 HTTP，而非每个关键词一次）后一次性写入 knowledge_chunk（base_id, keyword, embedding_vec）。
     * 关键词全部去重；若关键词为空但向量可用，用截断后的正文作为单个关键词兜底，保证条目可被检索。
     *
     * <p><b>注意</b>：{@link EmbeddingClient#embed} 内部是同步阻塞 HTTP，必须切到 {@code boundedElastic}，
     * 否则会阻塞 Reactor Netty 事件循环线程。
     */
    private Mono<Void> writeChunks(Long baseId, String content, int chunkSize) {
        boolean on = embeddingClient != null && embeddingClient.isEnabled();
        return extractKeywords(content, chunkSize)
                .flatMap(keywords -> {
                    List<String> raw = (keywords == null || keywords.isEmpty())
                            ? (on && content != null && !content.isBlank()
                                ? List.of(SemanticRefineHandler.safeClamp(content)) : List.of())
                            : keywords;
                    List<String> kws = dedupNonBlank(raw);
                    if (kws.isEmpty()) {
                        return Mono.empty();
                    }
                    if (!on) {
                        return insertKeywords(baseId, kws, null);
                    }
                    // 一次批量向量化（Ollama /api/embed），避免 N 个关键词发 N 次 HTTP
                    return Mono.fromCallable(() -> embeddingClient.embed(kws))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(vecs -> insertKeywords(baseId, kws, toVecJsons(kws, vecs)))
                            .onErrorResume(e -> {
                                // 向量化失败也要把关键词落库（向量留空），保证数据不丢、后续可重建索引
                                log.warn("[Knowledge] 关键词批量向量化失败，改为写入无向量关键词: {}", e.getMessage());
                                return insertKeywords(baseId, kws, null);
                            });
                });
    }

    /** 去空 + trim 后去重，保持顺序 */
    private static List<String> dedupNonBlank(List<String> src) {
        if (src == null || src.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String s : src) {
            if (s == null) {
                continue;
            }
            String kw = s.trim();
            if (!kw.isEmpty()) {
                seen.add(kw);
            }
        }
        return new ArrayList<>(seen);
    }

    /** 把批量向量化结果转成与关键词一一对应的向量 JSON 列表（缺失/异常位置为 null） */
    private static List<String> toVecJsons(List<String> kws, List<float[]> vecs) {
        List<String> out = new ArrayList<>(kws.size());
        for (int i = 0; i < kws.size(); i++) {
            float[] v = (vecs != null && i < vecs.size()) ? vecs.get(i) : null;
            out.add(v != null && v.length > 0 ? VectorUtils.toVecJson(v) : null);
        }
        return out;
    }

    private Mono<List<String>> extractKeywords(String content, int chunkSize) {
        if (content == null || content.isBlank()) {
            return Mono.just(List.of());
        }
        final int cs = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        if (content.length() <= SEGMENT_MIN_CHARS) {
            return semanticRefineHandler.extractKeywords(content, 0);
        }
        return semanticRefineHandler.segment(content, cs)
                .flatMapMany(chunks -> chunks.isEmpty() ? Flux.empty() : Flux.fromIterable(chunks))
                .flatMap(chunk -> semanticRefineHandler.extractKeywords(chunk, 0))
                .reduce(new LinkedHashSet<String>(), (set, list) -> {
                    if (list != null) {
                        for (String k : list) {
                            if (k != null) {
                                set.add(k.trim());
                            }
                        }
                    }
                    return set;
                })
                .map(set -> (List<String>) new ArrayList<>(set))
                .defaultIfEmpty(new ArrayList<>());
    }

    /**
     * 批量写入 knowledge_chunk：一条多值 INSERT 落全部关键词（每个关键词一行、各带自己的向量）。
     * {@code vecJsons} 与 {@code keywords} 一一对应，元素为 null 表示该关键词无向量（embedding_vec 留 NULL）。
     */
    private Mono<Void> insertKeywords(Long baseId, List<String> keywords, List<String> vecJsons) {
        if (keywords == null || keywords.isEmpty()) {
            return Mono.empty();
        }
        StringBuilder sql = new StringBuilder("INSERT INTO knowledge_chunk (base_id, keyword, embedding_vec, is_delete) VALUES ");
        Map<String, Object> p = new LinkedHashMap<>(keywords.size() * 2 + 1);
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            p.put("kw" + i, keywords.get(i));
            p.put("baseId" + i, baseId);
            String vj = (vecJsons != null && i < vecJsons.size()) ? vecJsons.get(i) : null;
            if (vj != null) {
                p.put("vec" + i, vj);
                sql.append("(:baseId").append(i).append(", :kw").append(i)
                        .append(", STRING_TO_VECTOR(:vec").append(i).append("), 0)");
            } else {
                sql.append("(:baseId").append(i).append(", :kw").append(i).append(", NULL, 0)");
            }
        }
        return r2dbcClient.execute(sql.toString(), p)
                .timeout(BATCH_TIMEOUT)
                .then();
    }

    /** 单条知识库命中：条目 ID + 正文 + 最高相似度 + 命中关键词（已拼接） */
    private static final class KnowledgeHit {
        final Long baseId;
        final String content;
        final double score;
        final String keyword;

        KnowledgeHit(Long baseId, String content, double score, String keyword) {
            this.baseId = baseId;
            this.content = content;
            this.score = score;
            this.keyword = keyword;
        }
    }

    /** base_id 聚合：最高相似度 + 命中关键词集合 */
    private static final class BaseAgg {
        double score = 0d;
        final LinkedHashSet<String> keywords = new LinkedHashSet<>();

        String keyword() {
            List<String> top = new ArrayList<>(keywords);
            if (top.size() > 5) {
                top = top.subList(0, 5);
            }
            return String.join("、", top);
        }
    }
}
