package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内消息视图对象（推送给前端的载荷，同时也是 SSE 事件 data）。
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMessageVO {

    /** 消息ID */
    private Long id;

    /** 消息类型：info / success / warn / error */
    private String type;

    /** 消息标题 */
    private String title;

    /** 消息内容 */
    private String content;

    /** 点击跳转（前端视图名） */
    private String link;

    /** 是否已读 */
    private Boolean read;

    /** 创建时间 */
    private LocalDateTime createTime;
}
