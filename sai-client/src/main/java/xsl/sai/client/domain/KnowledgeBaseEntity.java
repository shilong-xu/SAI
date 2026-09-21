package xsl.sai.client.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

/**
 * 知识库条目（RAG，给 Agent 检索）
 *
 * <p>一条记录即一个知识库条目，包含文本内容；
 * 新增/修改时由服务层语义切割 + 向量化，切块落入 {@code knowledge_chunk} 表。
 *
 * @author SAI
 */
@Getter
@Setter
@Table("knowledge_base")
public class KnowledgeBaseEntity extends BaseEntity {

    @Id
    private Long id;

    /** 文本内容 */
    private String content;

    // 注意：remark 继承自 BaseEntity，此处不可重复声明 ——
    // 子类同名字段会遮蔽父类字段，导致 Spring Data R2DBC 写入时读到父类的 null 值，落库后 remark 为 NULL。
}
