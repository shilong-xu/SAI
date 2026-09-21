package xsl.sai.client.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.agent.AgentAI;
import xsl.sai.client.domain.ReceivedEmailEntity;

import java.time.Duration;

/**
 * 邮件 → Agent 分发器：把「刚抓取入库的新邮件」提交给 Agent 处理。
 *
 * <p><b>开关</b>：{@code spring.mail.receive.dispatch-to-agent}（默认 false）。
 * 关闭时本类完全惰性——{@code fetchAndStore} 只入库、不做任何模型调用；
 * 开启时每封新邮件会被组装成一段「无人值守」指令提交给主 Agent，
 * 由它按其规则（邮件分类技能 + 待办子智能体）判断是否需要登记为待办。
 *
 * <p><b>为什么异步 fire-and-forget</b>：Agent 处理要调模型、可能要几十秒；
 * 若挂进抓取链路，前端「抓取邮件」按钮与每 5 分钟的定时任务都会被拖住。
 * 这里在 {@code boundedElastic} 上独立订阅，抓取接口/定时任务立即返回本次入库数量，
 * Agent 的处理结果只落日志（其可见产出是「待办事项」页面里新增的条目）。
 *
 * <p><b>为什么不会死循环</b>：只在<b>接收</b>链路（{@link xsl.sai.client.service.EmailReceiveService#fetchAndStore()}）
 * 调用；发送链路走 {@code EmailSentEvent → saveSent}，不经过本类。
 * 因而「Agent 处理邮件 → 生成待办 / 草稿」不会反过来又变成一封新邮件被再次处理。
 *
 * <p><b>会话隔离</b>：每封邮件用一个独立会话（{@code email-bot-<邮件ID>}）。
 * 框架对同一会话只允许一条后台主循环（新消息会打断上一条），
 * 用独立会话既能并发处理多封邮件，也避免互相打断。
 *
 * @author SAI
 */
@Slf4j
@Component
public class MailAgentDispatcher {

    /**
     * 邮件流水线使用的用户身份：与 {@code ScheduleTask} 的定时任务身份一致
     * （md5("admin")，即后台管理员账号），使后台触发的 Agent 运行归属同一身份。
     */
    private static final String PIPELINE_USER_ID = "21232f297a57a5a743894a0e4a801fc3";

    /** 流水线标记：触发 AGENTS.md 的「无人值守」约定（自主完成、不发送邮件） */
    private static final String PIPELINE_TAG = "[邮件流水线-无人值守模式] ";

    /** 会话 ID 前缀（每封邮件一个独立会话：email-bot-<邮件ID>） */
    private static final String SESSION_PREFIX = "email-bot-";

    private final AgentAI agentAI;

    /** 是否把新邮件提交给 Agent 处理 */
    private final boolean enabled;
    /** 提交给 Agent 的正文最大字符数（超出截断，控制 token 消耗） */
    private final int maxContentChars;
    /** 单封邮件处理的超时（分钟） */
    private final long timeoutMinutes;

    public MailAgentDispatcher(AgentAI agentAI,
                               @Value("${spring.mail.receive.dispatch-to-agent:false}") boolean enabled,
                               @Value("${spring.mail.receive.agent-content-chars:4000}") int maxContentChars,
                               @Value("${spring.mail.receive.agent-timeout-minutes:5}") long timeoutMinutes) {
        this.agentAI = agentAI;
        this.enabled = enabled;
        this.maxContentChars = maxContentChars > 0 ? maxContentChars : 4000;
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : 5;
        log.info("[Email·Agent] 邮件抓取后提交 Agent 处理：{}（正文上限 {} 字符，超时 {} 分钟）",
                enabled ? "已开启" : "已关闭", this.maxContentChars, this.timeoutMinutes);
    }

    /** 开关状态（供抓取链路判断是否已提交给 Agent） */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 把一封新入库的邮件异步提交给 Agent 处理。
     *
     * <p>本方法<b>立即返回</b>，不阻塞调用方；处理结果（成功 / 失败 / 超时）只写日志。
     * 开关关闭、邮件为空或未入库（无 ID）时直接跳过。
     *
     * @param mail 刚入库的接收邮件（已带自增 ID）
     */
    public void dispatchAsync(ReceivedEmailEntity mail) {
        if (!enabled || mail == null || mail.getId() == null) {
            return;
        }
        final Long mailId = mail.getId();
        final String sessionId = SESSION_PREFIX + mailId;
        final String prompt = buildPrompt(mail);

        Mono<String> task = Mono.defer(() -> agentAI.process(PIPELINE_USER_ID, sessionId, prompt)
                        .collectList()
                        .map(deltas -> (deltas == null || deltas.isEmpty()) ? "(无回复)" : String.join("", deltas)))
                .timeout(Duration.ofMinutes(timeoutMinutes), Mono.just("(处理超时，已放弃等待)"))
                .onErrorResume(e -> {
                    log.error("[Email·Agent] 邮件#{} 处理异常 --> {}", mailId, e.getMessage(), e);
                    return Mono.just("(处理失败: " + e.getMessage() + ")");
                });

        // 独立订阅 + 切到 boundedElastic：不占用抓取链路线程，也不随抓取请求结束而被取消
        task.subscribeOn(Schedulers.boundedElastic())
                .subscribe(reply -> log.info("[Email·Agent] 邮件#{} 处理完成，回复摘要：{}", mailId, brief(reply)),
                        e -> log.error("[Email·Agent] 邮件#{} 订阅异常 --> {}", mailId, e.getMessage(), e));
    }

    /**
     * 组装提交给 Agent 的指令：邮件要素 + 处理要求。
     *
     * <p>要求只交代**目标与边界**（交给邮件子智能体、本模式不发送），
     * <b>不写具体步骤</b>——分类判定、字段映射、查重规则等属于子智能体职责，
     * 统一下沉在 {@code subagents/EmailAgent.md} 的「邮件流水线」与 {@code skills/mail-todo} 里，
     * 避免同一套规则在主提示词 / 本指令 / 子智能体三处各写一遍而漂移。
     */
    private String buildPrompt(ReceivedEmailEntity mail) {
        String fromName = blankToDefault(mail.getFromName(), "");
        String addr = blankToDefault(mail.getFromAddr(), "(未知发件人)");
        String fromText = fromName.isBlank() ? addr : fromName + " <" + addr + ">";
        String subject = blankToDefault(mail.getSubject(), "(无主题)");
        String content = mail.getContent() == null ? "" : mail.getContent();
        if (content.length() > maxContentChars) {
            content = content.substring(0, maxContentChars) + "\n…（正文过长已截断）";
        }
        return PIPELINE_TAG + "收到一封新邮件，请按邮件转待办规则处理。\n\n"
                + "【邮件ID】" + mail.getId() + "\n"
                + "【发件人】" + fromText + "\n"
                + "【主题】" + subject + "\n"
                + "【正文】\n" + content + "\n\n"
                + "处理要求：\n"
                + "1. 交给邮件子智能体处理（邮件分类 → 是否需要登记为待办事项），按其既有流程走完；\n"
                + "2. 本邮件来自邮件流水线（无人值守）：只做分类与待办登记，绝不发送任何邮件。\n";
    }

    /** 空白（null / 空串 / 全空白）→ 默认值 */
    private static String blankToDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    /** 日志用的短摘要（避免把整段回复写进日志） */
    private static String brief(String text) {
        if (text == null) {
            return "(null)";
        }
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
