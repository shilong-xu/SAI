package xsl.sai.agent.tool;

import cn.hutool.json.JSONUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.MailClient;
import xsl.sai.framework.client.MailReceiver;
import xsl.sai.framework.event.EmailSentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件工具（收发一体）—— 把 {@link MailClient}（发）与 {@link MailReceiver}（收）
 * 暴露为 Agent 可调用的两个工具：
 * <ul>
 *   <li>{@code send_mail}：发送邮件，支持纯文本与 HTML 正文；</li>
 *   <li>{@code receive_mail}：拉取收件箱新邮件，以结构化 JSON 列表返回。</li>
 * </ul>
 *
 * <p>二者由同一个 Bean 提供，收/发共用同一份邮箱配置（{@code spring.mail.*}），
 * 由邮件中枢子智能体 EmailAgent 统一调用。
 *
 * <p><b>边界</b>：{@code receive_mail} 只做「拉取 + 返回 JSON」，<b>不入库</b>；
 * 去重、入库与向量化由客户端定时任务 {@code emailReceiveService.fetchAndStore()} 负责，
 * 避免“Agent 拉一次 + 定时器拉一次”造成重复写库。
 *
 * <p><b>纯流式、零阻塞</b>：两个方法均返回 {@link Mono<String>}，底层已封装为响应式。
 * 邮件服务未配置时统一返回友好提示，不抛异常中断 Agent 流程。
 *
 * @author SAI
 */
@Slf4j
@Component
public class MailTool {

    private final MailClient mailClient;
    private final MailReceiver mailReceiver;
    private final ApplicationEventPublisher eventPublisher;

    public MailTool(MailClient mailClient, MailReceiver mailReceiver,
                    ApplicationEventPublisher eventPublisher) {
        this.mailClient = mailClient;
        this.mailReceiver = mailReceiver;
        this.eventPublisher = eventPublisher;
    }

    // ==================== 发送 ====================

    @Tool(name = "send_mail",
          description = "发送邮件。支持纯文本或 HTML 正文，可指定收件人和主题。"
                  + "适用于通知、报告、告警等需要邮件分发的场景。"
                  + "仅当邮件服务已配置（spring.mail.host 非空）时才可正常发送。")
    public Mono<String> sendMail(
            RuntimeContext runtimeContext,
            @ToolParam(name = "to", required = true,
                       description = "收件人邮箱地址，多个用逗号分隔") String to,
            @ToolParam(name = "subject", required = true,
                       description = "邮件主题") String subject,
            @ToolParam(name = "content", required = true,
                       description = "邮件正文") String content,
            @ToolParam(name = "html", required = false,
                       description = "正文是否为 HTML 格式，默认 false（纯文本）") Boolean html) {

        if (!mailClient.isEnabled()) {
            return Mono.just("邮件服务未配置（spring.mail.host 为空），无法发送。"
                    + "请在 application-framework.yml 中配置 spring.mail.host / username / password 等相关参数。");
        }

        boolean isHtml = html != null && html;
        // 触发本次发送的会话用户（供 sai-client 定向推送站内消息；后台场景为系统身份，监听侧会转为广播）
        final String uid = runtimeContext == null ? null : runtimeContext.getUserId();
        return (isHtml ? mailClient.sendHtml(to, subject, content) : mailClient.sendText(to, subject, content))
                .then(Mono.fromCallable(() -> {
                    log.info("Agent 调用 send_mail 成功: to={}, subject={}", to, subject);
                    publishSentEvent(uid, to, subject, content, isHtml, true, null);
                    return "邮件发送成功 → 收件人: " + to + ", 主题: " + subject;
                }))
                .onErrorResume(e -> {
                    log.error("Agent 调用 send_mail 失败: to={}, subject={}, error={}", to, subject, e.getMessage());
                    publishSentEvent(uid, to, subject, content, isHtml, false, e.getMessage());
                    return Mono.just("邮件发送失败: " + e.getMessage());
                });
    }

    /**
     * 发布「邮件发送完成」事件（成功 / 失败均发；发布失败仅告警，不影响工具返回）。
     *
     * <p>携带正文原文，供 sai-client 在发送成功后写入 {@code received_email}
     * （{@code direction=1}「发送」）留痕；失败时监听侧只发站内通知、不入库。
     */
    private void publishSentEvent(String userId, String to, String subject, String content, boolean html,
                                  boolean success, String errorMsg) {
        try {
            eventPublisher.publishEvent(EmailSentEvent.builder()
                    .userId(userId)
                    .to(to)
                    .subject(subject)
                    .content(content)
                    .html(html)
                    .success(success)
                    .errorMsg(errorMsg)
                    .build());
        } catch (Exception e) {
            log.warn("邮件发送事件发布失败 to={} --> {}", to, e.getMessage());
        }
    }

    // ==================== 接收 ====================

    @Tool(name = "receive_mail",
          description = "接收/拉取收件箱中的新邮件，并以结构化 JSON 列表返回（每封含 from/fromName/subject/content/"
                  + "hasAttachment/attachCount/sentDate/messageId）。当用户要求『检查邮件/收件箱有什么新邮件/"
                  + "读取最新邮件/接收邮件』时调用。邮件服务未配置时返回友好提示。")
    public Mono<String> receiveMail(
            RuntimeContext runtimeContext,
            @ToolParam(name = "max", required = false,
                       description = "最多拉取的邮件数，默认 10") Integer max) {
        int limit = (max != null && max > 0) ? max : 10;
        if (!mailReceiver.isEnabled()) {
            return Mono.just("邮件接收服务未配置（spring.mail.receive.host 为空或邮箱账号/授权码为空），"
                    + "无法接收邮件。请在 application-framework.yml 中配置 spring.mail.receive.host "
                    + "与 spring.mail.username/password。");
        }
        return mailReceiver.fetchRecent()
                .map(list -> toJson(list, limit))
                .switchIfEmpty(Mono.just("收件箱暂无新邮件（或邮件均已读过）。"))
                .onErrorResume(e -> {
                    log.error("Agent 调用 receive_mail 失败: {}", e.getMessage(), e);
                    return Mono.just("邮件接收失败: " + e.getMessage());
                });
    }

    /** 将邮件列表转为带围栏的 JSON 字符串（最多取前 limit 封） */
    private String toJson(List<MailReceiver.ReceivedMail> list, int limit) {
        int end = Math.min(list.size(), limit);
        List<Map<String, Object>> rows = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
            MailReceiver.ReceivedMail m = list.get(i);
            Map<String, Object> row = new LinkedHashMap<>(8);
            row.put("from", m.fromAddr());
            row.put("fromName", m.fromName());
            row.put("subject", m.subject());
            row.put("content", m.content());
            row.put("hasAttachment", m.hasAttachment());
            row.put("attachCount", m.attachCount());
            row.put("sentDate", m.sentDate() == null ? "" : m.sentDate().toString());
            row.put("messageId", m.messageId());
            rows.add(row);
        }
        return "共接收到 " + list.size() + " 封邮件（展示前 " + end + " 封），JSON 如下：\n```json\n"
                + JSONUtil.toJsonPrettyStr(rows) + "\n```";
    }
}
