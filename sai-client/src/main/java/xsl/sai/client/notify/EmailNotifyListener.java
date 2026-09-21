package xsl.sai.client.notify;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import xsl.sai.framework.event.EmailSentEvent;

import java.util.Set;

/**
 * 邮件发送完成 → 站内消息通知（监听 Spring 事件，与 sai-agent 解耦）
 *
 * <p>agent 模块只发布 {@link EmailSentEvent}，不感知通知实现；本监听器把
 * {@code send_mail} 的发送结果转为站内消息：前端铃铛 +1 并弹 toast。
 * 无交互触发时（如定时任务抓取邮件后自动回复），收件人无对话用户，转为全员广播。
 *
 * <p>通知策略：
 * <ul>
 *   <li>SUCCESS → success 类型，含收件人与主题；</li>
 *   <li>FAIL → error 类型，含失败原因。</li>
 * </ul>
 *
 * <p><b>收件人解析</b>：会话用户为真实登录用户时定向推送；为系统身份
 * （邮件流水线 {@code email-bot}、兜底 {@code SAI}）或为空时改为广播
 * （{@code userId=null}，所有用户可见），否则通知会推给一个不存在的"用户"而无人能看到。
 *
 * @author SAI
 */
@Slf4j
@Component
public class EmailNotifyListener {

    /** 系统身份：非真实登录用户，其发起的邮件通知改为全员广播 */
    private static final Set<String> SYSTEM_USERS = Set.of("email-bot", "SAI");

    /** 主题过长时截断，避免撑爆标题列 */
    private static final int MAX_SUBJECT = 30;

    @EventListener(EmailSentEvent.class)
    public void onEmailSent(EmailSentEvent event) {
        try {
            NotifyClient.sendAsync(resolveUserId(event.getUserId()), resolveType(event),
                    resolveTitle(event), resolveContent(event), null);
        } catch (Exception e) {
            // 通知失败不影响邮件发送主流程
            log.warn("[Notify] 邮件发送通知发送失败 to={} --> {}", event.getTo(), e.getMessage());
        }
    }

    /** 真实登录用户 → 定向推送；系统身份 / 空 → 全员广播（null） */
    private static String resolveUserId(String userId) {
        return (StrUtil.isBlank(userId) || SYSTEM_USERS.contains(userId)) ? null : userId;
    }

    private static String resolveType(EmailSentEvent e) {
        return e.isSuccess() ? "success" : "error";
    }

    private static String resolveTitle(EmailSentEvent e) {
        String subject = StrUtil.blankToDefault(e.getSubject(), "(无主题)");
        return (e.isSuccess() ? "邮件已发送：" : "邮件发送失败：") + StrUtil.maxLength(subject, MAX_SUBJECT);
    }

    private static String resolveContent(EmailSentEvent e) {
        String to = StrUtil.blankToDefault(e.getTo(), "(未填收件人)");
        String subject = StrUtil.blankToDefault(e.getSubject(), "(无主题)");
        if (e.isSuccess()) {
            return "收件人：" + to + "；主题：" + subject + (e.isHtml() ? "（HTML 正文）" : "") + "。";
        }
        return "收件人：" + to + "；主题：" + subject
                + "。失败原因：" + StrUtil.blankToDefault(e.getErrorMsg(), "未知原因");
    }
}
