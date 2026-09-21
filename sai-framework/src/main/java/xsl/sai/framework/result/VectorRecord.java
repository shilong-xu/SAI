package xsl.sai.framework.result;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 向量记录 —— 持久层对“一行向量数据”的标准载体
 *
 * <p>同时覆盖查询 / 检索 / 写入三种场景，包含四大件：
 * <ul>
 *   <li><b>content</b> —— 原始文本内容（如 chunk_text）</li>
 *   <li><b>metadata</b> —— 元数据（如 id、title、source、chunk_index 等）</li>
 *   <li><b>embedding</b> —— 向量本身（写入/返回时使用）</li>
 *   <li><b>score</b> —— 相似度分数（非持久化字段；仅检索返回时由 {@code R2dbcClient} 填充，余弦相似度 0~1，越小越不相关）</li>
 * </ul>
 *
 * @DATE: 2026/6/23
 * @AUTHOR: XSL
 */
@Data
public class VectorRecord {

    /**
     * 原始文本内容
     */
    private String content;

    /**
     * 元数据（id、title、source 等）
     */
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /**
     * 向量
     */
    private float[] embedding;

    /**
     * 相似度分数（仅检索返回时填充，非持久化字段）
     */
    private double score;

    // ==================== 构造 ====================

    public VectorRecord() {
    }

    public VectorRecord(String content, Map<String, Object> metadata, double score) {
        this.content = content;
        this.metadata = metadata != null ? metadata : new LinkedHashMap<>();
        this.score = score;
    }

    public VectorRecord(String content, Map<String, Object> metadata, float[] embedding, double score) {
        this.content = content;
        this.metadata = metadata != null ? metadata : new LinkedHashMap<>();
        this.embedding = embedding;
        this.score = score;
    }

    // ==================== 便利方法 ====================

    public VectorRecord content(String content) {
        this.content = content;
        return this;
    }

    public VectorRecord metadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    public VectorRecord putMeta(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public VectorRecord embedding(float[] embedding) {
        this.embedding = embedding;
        return this;
    }

    public VectorRecord score(double score) {
        this.score = score;
        return this;
    }

    // ==================== getter / setter ====================

    /**
     * 分数百分比展示
     */
    public String getScorePct() {
        return String.format(Locale.ROOT, "%.1f%%", score * 100);
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VectorRecord{");
        sb.append("score=").append(String.format("%.4f", score));
        if (content != null && content.length() > 80) {
            sb.append(", content='").append(content, 0, 80).append("...'");
        } else {
            sb.append(", content='").append(content).append("'");
        }
        sb.append(", metadata=").append(metadata);
        sb.append(", hasEmbedding=").append(embedding != null);
        sb.append("}");
        return sb.toString();
    }
}
