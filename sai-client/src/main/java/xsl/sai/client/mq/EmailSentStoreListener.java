package xsl.sai.client.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import xsl.sai.client.service.EmailReceiveService;
import xsl.sai.framework.event.EmailSentEvent;

/**
 * 邮件发送完成 → 写入邮件表留痕（{@code direction=1}「发送」）
 *
 * <p>与 {@code MailTool} 解耦：agent 模块只发布 {@link EmailSentEvent}，
 * 由本监听器在 client 侧落到 {@code received_email}（收发同表，用 {@code direction} 区分）。
 * 发件人与显示名由 Service 从当前邮箱配置取值，事件只需带收件人 / 主题 / 正文。
 *
 * <p><b>只记录发送成功的邮件</b>：失败的事件带 {@code success=false}，此处直接跳过——
 * 表里不该出现一封实际没发出去的「发送记录」（失败仍有站内通知，由通知监听器负责）。
 *
 * <p><b>仅作记录</b>：入库走的是 {@code saveSent}，接收流程已取消事件总线发布 / Agent 处理，
 * 不存在「回复邮件 → 入库 → 再次被处理」的循环。
 *
 * @author SAI
 */
@Slf4j
@Component
public class EmailSentStoreListener {

    private final EmailReceiveService emailReceiveService;

    public EmailSentStoreListener(EmailReceiveService emailReceiveService) {
        this.emailReceiveService = emailReceiveService;
    }

    @EventListener(EmailSentEvent.class)
    public void onEmailSent(EmailSentEvent event) {
        if (!event.isSuccess()) {
            return;
        }
        try {
            // 库写入是响应式订阅，异步执行；失败仅告警，不影响发送结果
            emailReceiveService.saveSent(new EmailReceiveService.SentMail(
                            event.getUserId(), event.getTo(), event.getSubject(), event.getContent()))
                    .subscribe(v -> { },
                            e -> log.warn("[Email] 发送邮件入库失败 to={} --> {}", event.getTo(), e.getMessage()));
        } catch (Exception e) {
            log.warn("[Email] 发送邮件入库异常 to={} --> {}", event.getTo(), e.getMessage());
        }
    }
}
