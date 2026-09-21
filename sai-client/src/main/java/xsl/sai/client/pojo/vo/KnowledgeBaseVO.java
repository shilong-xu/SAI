package xsl.sai.client.pojo.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库条目视图对象（列表/详情返回）
 *
 * @author SAI
 */
@Getter
@Setter
public class KnowledgeBaseVO {

    private Long id;
    private String content;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 该条目已提炼并索引的全部关键词（列表接口批量回填，取自 knowledge_chunk.keyword） */
    private List<String> keywords;
    /** 语义检索命中的关键词（knowledge_chunk.keyword） */
    private String keyword;
    /** 关联的知识库条目 ID（语义检索命中关键词时填充） */
    private Long baseId;
    /** 语义检索相似度 0~1（COSINE，越大越相关） */
    private Double score;
}
