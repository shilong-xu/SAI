package xsl.sai.client.pojo.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 待办事项 视图对象
 *
 * @author SAI
 */
@Getter
@Setter
public class TodoItemVO {

    private Long id;

    /** 标题 */
    private String title;

    /**
     * 类型：数字 code（1 任务 / 2 事项 / 3 会议 / 4 回信），见
     * {@link xsl.sai.framework.enums.TodoType}；前端按 code 渲染中文标签。
     */
    private Integer type;

    /** 待办内容（Markdown 文本，由前端渲染） */
    private String content;

    /** 是否完成（0 未完成 / 1 已完成） */
    private Integer done;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
