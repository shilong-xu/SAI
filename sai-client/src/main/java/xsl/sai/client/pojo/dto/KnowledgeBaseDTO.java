package xsl.sai.client.pojo.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库条目新增/修改入参
 *
 * @author SAI
 */
@Getter
@Setter
public class KnowledgeBaseDTO implements Serializable {

    /** 主键（修改时必填，新增时空） */
    private Long id;

    /** 文本内容 */
    private String content;

    /** 备注 */
    private String remark;

    /**
     * 手动指定的关键词（用于向量索引的检索入口）。
     *
     * <p><b>null = 交给模型自动提炼</b>（默认行为）；非 null（含空数组）= 完全以本字段为准，
     * 不再调用提炼模型 —— 前端编辑弹窗里用户增删改后的结果即走这条路径。
     */
    private List<String> keywords;
}
