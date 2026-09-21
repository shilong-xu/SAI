package xsl.sai.client.notify;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.NotifyMessageVO;
import xsl.sai.client.service.NotifyService;
import xsl.sai.framework.holder.SpringHolder;

/**
 * 消息提醒客户端（业务侧统一入口，无需注入即可调用）。
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * // 响应式：可继续编排（推荐在 webflux 链路内使用）
 * return someWork().then(NotifyClient.send(userId, "任务完成", "xxx 已执行成功"));
 *
 * // 即发即忘：用于定时任务 / 回调等非响应式上下文
 * NotifyClient.sendAsync(userId, "warn", "任务失败", "xxx 执行异常", "schedule-log");
 * }</pre>
 *
 * <p><b>与 SseClient 的分工</b>：
 * <ul>
 *   <li>{@code NotifyClient}：面向业务的"消息提醒"，落库 + 已读 + 推送，按用户隔离；</li>
 *   <li>{@code SseClient}：底层通道，纯推送、不落库，可用于广播类系统事件。</li>
 * </ul>
 *
 * @author SAI
 */
@Slf4j
@Component
public class NotifyClient {

    private static NotifyService notifyService;

    @PostConstruct
    public void init() {
        try {
            NotifyClient.notifyService = SpringHolder.getBean(NotifyService.class);
        } catch (Exception e) {
            log.warn("NotifyService 未初始化，消息提醒功能不可用：{}", e.getMessage());
            NotifyClient.notifyService = null;
        }
    }

    private static NotifyService service() {
        NotifyService s = notifyService;
        if (s == null) {
            throw new IllegalStateException("NotifyService 未初始化，消息提醒不可用");
        }
        return s;
    }

    /* ============================================================
     * 发送（响应式，返回可继续编排的 Mono）
     * ============================================================ */

    /** 发送一条 info 消息 */
    public static Mono<NotifyMessageVO> send(String userId, String title, String content) {
        return send(userId, "info", title, content, null);
    }

    /**
     * 发送一条消息
     *
     * @param userId  接收用户；传 null 表示全员广播（所有用户可见）
     * @param type    info / success / warn / error（未识别按 info）
     * @param title   标题
     * @param content 内容
     * @param link    点击跳转的前端视图名（可空）
     */
    public static Mono<NotifyMessageVO> send(String userId, String type, String title, String content, String link) {
        return Mono.defer(() -> service().push(userId, type, title, content, link))
                .onErrorResume(e -> {
                    log.error("[Notify] 消息发送失败 userId={} title={} --> {}", userId, title, e.getMessage());
                    return Mono.empty();
                });
    }

    /* ============================================================
     * 即发即忘（定时任务 / 回调等非响应式上下文）
     * ============================================================ */

    /** {@link #send(String, String, String)} 的即发即忘版本 */
    public static void sendAsync(String userId, String title, String content) {
        sendAsync(userId, "info", title, content, null);
    }

    /** {@link #send(String, String, String, String, String)} 的即发即忘版本 */
    public static void sendAsync(String userId, String type, String title, String content, String link) {
        send(userId, type, title, content, link).subscribe(
                null,
                e -> log.error("[Notify] 异步消息发送失败 userId={} --> {}", userId, e.getMessage())
        );
    }
}
