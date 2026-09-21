package xsl.sai.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 长期记忆召回结果 —— 一条命中记录 + 相似度
 *
 * <p>由 {@code AgentMemoryService#vectorRecall} 返回，最终被拼装进注入文本。
 * 与 {@code VectorRecord} 的区别：本类只携带记忆场景需要的字段，避免通用载体的冗余映射。
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemoryHit {

    /** 记忆主键 */
    private Long id;

    /** 记忆正文 */
    private String content;

    /** 记忆类型 FACT | EVENT | PREFERENCE | SUMMARY */
    private String memoryType;

    /** 来源 RAW | EXTRACT | TOOL | MANUAL */
    private String source;

    /**
     * 余弦相似度 [0,1]，越大越相关
     * （对应 SQL 中 {@code 1 - myvector_distance(..., 'COSINE')}）
     */
    private double score;
}
