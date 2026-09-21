package xsl.sai.framework.client;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import xsl.sai.framework.pojo.bo.SseEventBO;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 长连接推送客户端（零新增依赖，基于 WebFlux 自带能力）。
 *
 * <p><b>定位</b>：底层单向推送通道。业务侧通过静态方法把事件推到广播总线，
 * 已建立的 SSE 连接在订阅侧按 userId 过滤后收到事件。
 * 需要"落库 + 已读"这类业务语义的通知，请使用上层的 {@code NotifyClient}。
 *
 * <p><b>用法</b>（任意位置，无需注入）：
 * <pre>{@code
 * SseClient.sendTo(userId, "notify", vo);   // 推给指定用户
 * SseClient.broadcast("system", data);      // 广播给所有在线连接
 * }</pre>
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>总线为 {@code Sinks.many().multicast().directBestEffort()}，多订阅者共享，无订阅者时
 *       事件按 best-effort 丢弃（不报错）；订阅者断开不影响总线，断线重连后推送自动恢复；</li>
 *   <li>订阅流自带心跳注释帧（默认 15s），防止中间代理按空闲超时掐断连接；</li>
 *   <li>订阅流异常时返回空流而非抛错 —— 让浏览器按 {@code retry} 自动重连。</li>
 * </ul>
 *
 * <p><b>注意</b>：本类是 JVM 内广播，多实例部署时事件无法跨 JVM；
 * 届时可用 Redisson 的 {@code RTopic} 转发（当前单实例无需处理）。
 *
 * @author SAI
 */
@Slf4j
@Component
public class SseClient {

    private static SseClient instance;

    /**
     * 广播总线：所有事件统一入口，在订阅侧按 userId 过滤。
     *
     * <p>必须用 {@code directBestEffort()}：{@code onBackpressureBuffer()} 版本在最后一个订阅者
     * 断开后会进入 CANCELLED 且永久失效（新订阅者立即 complete，事件推送 FAIL_CANCELLED），
     * 表现为"浏览器刷新一次后实时推送再也不通、只剩心跳"。best-effort 无此生命周期问题，
     * 订阅者可随时加入/离开，断线重连后推送自动恢复。
     */
    private final Sinks.Many<SseEventBO> sink = Sinks.many().multicast().directBestEffort();

    /** 事件序号（同时作为 SSE 的 id 字段，供断线重连时定位） */
    private final AtomicLong seq = new AtomicLong();

    @Value("${sai.sse.enabled:true}")
    private boolean enabled;

    /** 心跳间隔（秒）：过长会被代理判空闲，过短浪费带宽 */
    @Value("${sai.sse.heartbeat-seconds:15}")
    private long heartbeatSeconds;

    /** 断线后浏览器自动重连的等待时间（秒） */
    @Value("${sai.sse.retry-seconds:3}")
    private long retrySeconds;

    @PostConstruct
    public void init() {
        SseClient.instance = this;
    }

    /**
     * 获取单例实例（供 SSE 端点订阅事件流）。
     *
     * @return SseClient 单例
     * @throws IllegalStateException 容器未初始化时抛出
     */
    public static SseClient instance() {
        SseClient c = instance;
        if (c == null) {
            throw new IllegalStateException("SseClient 未初始化，SSE 推送不可用");
        }
        return c;
    }

    /* ============================================================
     * 静态入口（业务侧调用）
     * ============================================================ */

    /**
     * 向指定用户推送事件（用户不在线则静默丢弃）
     *
     * @param userId 目标用户ID
     * @param event  SSE 事件名
     * @param data   事件数据（字符串原样发送，对象转 JSON）
     */
    public static void sendTo(String userId, String event, Object data) {
        if (userId == null) {
            broadcast(event, data);
            return;
        }
        SseEventBO bo = SseEventBO.builder()
                .userId(userId)
                .event(event)
                .data(toJson(data))
                .time(System.currentTimeMillis())
                .build();
        send(bo);
    }

    /** 广播事件：所有在线连接都能收到 */
    public static void broadcast(String event, Object data) {
        SseEventBO bo = SseEventBO.builder()
                .event(event)
                .data(toJson(data))
                .time(System.currentTimeMillis())
                .build();
        send(bo);
    }

    /** 推送原始事件对象 */
    public static void send(SseEventBO bo) {
        if (bo == null) {
            return;
        }
        SseClient c = instance;
        if (c == null) {
            // 容器未初始化，静默丢弃
            return;
        }
        if (!c.enabled) {
            // 开关关闭，静默丢弃
            return;
        }
        // best-effort 广播：无订阅者或瞬时繁忙时事件按预期丢弃（需落库的消息由上层先落库兜底）
        c.sink.tryEmitNext(bo);
    }

    /** data 序列化：String 原样，其余转 JSON */
    private static String toJson(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof String s) {
            return s;
        }
        return JSONUtil.toJsonStr(data);
    }

    /* ============================================================
     * 实例方法（SSE 端点订阅）
     * ============================================================ */

    /**
     * 订阅当前用户的事件流（含心跳），供 SSE 端点直接返回。
     *
     * @param userId 当前登录用户ID
     * @return SSE 事件流，异常时结束流（前端自动重连）
     */
    public Flux<ServerSentEvent<String>> subscribe(String userId) {
        Flux<ServerSentEvent<String>> events = sink.asFlux()
                .filter(e -> e.getUserId() == null || e.getUserId().equals(userId))
                .map(e -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .event(e.getEvent())
                        .data(e.getData())
                        .build());

        // 心跳：注释帧（: ping），不触发前端任何事件，仅保活
        Flux<ServerSentEvent<String>> beat = Flux.interval(Duration.ofSeconds(heartbeatSeconds))
                .map(t -> ServerSentEvent.<String>builder().comment("ping").build());

        // 首帧下发重连间隔，断线后浏览器按此间隔自动重连
        Flux<ServerSentEvent<String>> hello = Flux.just(
                ServerSentEvent.<String>builder()
                        .data("connected")
                        .retry(Duration.ofSeconds(retrySeconds))
                        .build());

        return Flux.concat(hello, Flux.merge(events, beat))
                .onErrorResume(e -> {
                    log.warn("[SSE] 事件流异常，结束流等待前端重连 userId={} --> {}", userId, e.getMessage());
                    return Flux.empty();
                });
    }
}
