package xsl.sai.framework.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 邮件发送完成事件（成功 / 失败均发布）
 *
 * <p><b>发布方</b>：sai-agent 的邮件工具（{@code MailTool#sendMail}，即 {@code send_mail} 调用结束）。
 *
 * <p><b>消费方</b>（sai-client，两个监听器各司其职）：
 * <ul>
 *   <li>{@code EmailSentStoreListener}：发送成功后写入 {@code received_email}（{@code direction=1}「发送」）留痕；</li>
 *   <li>{@code EmailNotifyListener}：转站内消息通知（前端铃铛 + toast）。</li>
 * </ul>
 *
 * <p>用 Spring 事件而非直接调用，是为了保持模块依赖方向：
 * agent 仅依赖 framework，不感知 client 的入库 / 通知实现。
 *
 * @author SAI
 */
@Getter
@Builder
@ToString
public class EmailSentEvent {

    /** 触发本次发送的会话用户（交互对话为登录用户；邮件流水线等后台场景为系统身份） */
    private final String userId;

    /** 收件人（多个以逗号分隔） */
    private final String to;

    /** 邮件主题 */
    private final String subject;

    /** 邮件正文原文（HTML 或纯文本，按 {@link #html} 判断） */
    private final String content;

    /** 正文是否为 HTML 格式 */
    private final boolean html;

    /** true=发送成功；false=发送失败 */
    private final boolean success;

    /** 失败原因（成功时为 null） */
    private final String errorMsg;
}
