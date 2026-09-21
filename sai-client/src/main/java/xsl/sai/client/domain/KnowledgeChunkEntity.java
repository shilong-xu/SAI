package xsl.sai.client.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

/**
 * 知识库关键词与向量
 *
 * <p>由 {@code knowledge_base.content} 经关键词提炼后生成，每个关键词单独向量化，
 * 向量存 {@code embeddingVec}（VECTOR(1024)），作为知识库语义检索的入口，
 * 检索命中后回填对应的 {@code knowledge_base} 条目。
 *
 * @author SAI
 */
@Getter
@Setter
@Table("knowledge_chunk")
public class KnowledgeChunkEntity extends BaseEntity {

    @Id
    private Long id;

    /** 关联 knowledge_base.id */
    private Long baseId;

    /** 关键词（由 knowledge_base 原文提炼，逐词向量化） */
    private String keyword;

    /** 向量（bge-m3, 1024维），仅用于持久层写入/读取，非表列直接映射 */
    private transient float[] embedding;

    /** 向量 JSON 字符串（写入库时用 STRING_TO_VECTOR 转换） */
    private transient String embeddingVec;
}
