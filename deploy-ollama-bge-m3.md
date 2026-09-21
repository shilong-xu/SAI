# bge-m3 向量化模型部署命令(Ollama)

> 仅覆盖「Ollama 部署 + bge-m3 向量化模型」,不含数据库与业务代码。
> 模型存储目录:`~/Documents/rag-knowledge-base/models`

---

## 1. 安装 Ollama
```bash
brew install ollama
ollama --version
```

## 2. 锁定模型存储目录(可选,推荐)
```bash
# 临时生效(每次起服务前执行)
export OLLAMA_MODELS=~/Documents/rag-knowledge-base/models

# 或开机自启(已生成 plist,OLLAMA_MODELS 已指向规定目录)
launchctl load ~/Library/LaunchAgents/com.user.ollama.plist
```

## 3. 启动 Ollama 服务
```bash
export OLLAMA_MODELS=~/Documents/rag-knowledge-base/models
ollama serve > ~/Documents/rag-knowledge-base/logs/ollama.log 2>&1 &

# 确认就绪(返回 200)
curl -s http://127.0.0.1:11434/api/tags
```

## 4. 拉取 bge-m3 模型
```bash
# 必须先导出 OLLAMA_MODELS,否则权重跑进默认目录
export OLLAMA_MODELS=~/Documents/rag-knowledge-base/models
ollama pull bge-m3

# 核对(应看到 bge-m3:latest)
ollama list
```

## 5. 验证模型可用(embed 冒烟测试)
```bash
curl -s -X POST http://127.0.0.1:11434/api/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"bge-m3","prompt":"测试文本"}'
# 返回 {"embedding":[1024 个 float], ...}
```

---

## 故障排查

### 拉取报 `could not connect to ollama server`
`ollama serve` 没在运行,先执行第 3 步再 pull。

### `/api/tags` 返回 `{"models":[]}`(服务在跑但读不到模型)
服务用了默认目录 `~/.ollama/models`,而模型在 `Documents/rag-knowledge-base/models`。
用软链让默认目录透明指向规定目录(不复制):
```bash
SRC=~/Documents/rag-knowledge-base/models
DST=~/.ollama/models
mkdir -p "$DST/blobs" "$DST/manifests/registry.ollama.ai/library/bge-m3"
for b in "$SRC"/blobs/*; do ln -sfn "$b" "$DST/blobs/$(basename "$b")"; done
for m in "$SRC"/manifests/registry.ollama.ai/library/bge-m3/*; do
  ln -sfn "$m" "$DST/manifests/registry.ollama.ai/library/bge-m3/$(basename "$m")"
done
# 重启服务后模型即可识别
```

---

## 速查
| 项 | 值 |
|----|----|
| 服务地址 | 127.0.0.1:11434 |
| 模型名 | bge-m3(1024 维,F16,上下文 8192) |
| 单条向量化接口 | POST `/api/embeddings` |
| 批量向量化接口 | POST `/api/embed` |
| 探活 | GET `/api/tags` |

> 上生产:把服务部署到服务器,Ollama / TEI / vLLM 均可,改地址即可,业务代码不动。
