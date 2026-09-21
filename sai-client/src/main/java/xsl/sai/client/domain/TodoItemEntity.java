package xsl.sai.client.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

/**
 * 待办事项（协作空间）
 *
 * <p>字段：标题、类型（任务/事项/会议/回信 等）、内容（Markdown 文本，前端渲染）、完成状态；
 * 创建/更新时间、备注、软删除由 {@link BaseEntity} 提供。
 *
 * @author SAI
 */
@Getter
@Setter
@Table("todo_item")
public class TodoItemEntity extends BaseEntity {

    @Id
    private Long id;

    /** 待办标题 */
    private String title;

    /**
     * 类型：数字 code，取值见 {@link xsl.sai.framework.enums.TodoType}
     * （1 任务 / 2 事项 / 3 会议 / 4 回信），可为 null 表示未指定。
     *
     * <p>落库列是 {@code tinyint}（<b>不带长度</b>）—— MySQL 的 {@code tinyint(1)}
     * 会被 R2DBC 当 Boolean 读，与这里的 Integer 冲突。
     */
    private Integer type;

    /** 待办内容（Markdown 文本，前端按 MD 渲染） */
    private String content;

    /** 是否完成（0 未完成 / 1 已完成） */
    private Integer done;
}
