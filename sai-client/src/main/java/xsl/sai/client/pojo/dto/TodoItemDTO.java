package xsl.sai.client.pojo.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 待办事项 新增/修改 请求体
 *
 * @author SAI
 */
@Getter
@Setter
public class TodoItemDTO {

    /** 编辑时传 id；新增时为空 */
    private Long id;

    /** 标题 */
    private String title;

    /**
     * 类型：数字 code（1 任务 / 2 事项 / 3 会议 / 4 回信），见
     * {@link xsl.sai.framework.enums.TodoType}；不传或非法值落库为 null（未指定）。
     */
    private Integer type;

    /** 待办内容（Markdown 文本） */
    private String content;

    /** 完成状态（0 未完成 / 1 已完成）；新增默认 0，编辑时可改 */
    private Integer done;
}
