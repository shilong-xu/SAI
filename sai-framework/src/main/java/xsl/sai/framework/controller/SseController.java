package xsl.sai.framework.controller;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import xsl.sai.framework.client.SseClient;
import xsl.sai.framework.config.AccountProvider;
import xsl.sai.framework.pojo.bo.RequestInfoBO;

/**
 * SSE 长连接端点（需登录）。
 *
 * <p>前端用 {@code new EventSource('/api/sse/stream?token=xxx')} 建立连接：
 * 原生 EventSource 不支持自定义请求头，令牌走 query 参数
 * （{@code AuthWebFilter} 已支持从 {@code ?token=} 解析）。
 *
 * <p><b>约定</b>：一个页面只开一条 SSE 连接，用事件名区分业务类型，
 * 避免 HTTP/1.1 下同域 6 连接上限被占满。
 *
 * <p><b>为什么这里不依赖 Reactor 上下文取用户</b>：SSE 是长驻流式响应，
 * 事件流在请求的 filter 上下文之外被订阅/写出，实测 {@code UserHolder} 在该
 * 路径读到 null，导致 {@link SseClient} 的定向事件（userId 匹配过滤）被全部
 * 丢弃，而连接与心跳却完全正常。因此本端点改为<b>直接解析请求自带 token</b>
 * 派生 userId（MD5(username)，与登录/推送侧完全一致），自包含、无上下文依赖。
 *
 * @author SAI
 */
@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
public class SseController {

    private final AccountProvider accountProvider;

    /**
     * 建立 SSE 长连接：返回当前用户的事件流（含心跳）。
     *
     * <p>{@code X-Accel-Buffering: no} 用于关闭 Nginx 等反向代理的响应缓冲，
     * 否则事件会被攒在缓冲区里延迟下发。
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(ServerWebExchange exchange, ServerHttpResponse response) {
        response.getHeaders().add("X-Accel-Buffering", "no");
        response.getHeaders().add("Cache-Control", "no-cache, no-transform");

        // 鉴权由 AuthWebFilter 完成（无效 token 到不了这里）；
        // userId 直接从 token 解析，不依赖 filter 写入的响应式上下文
        String token = resolveToken(exchange.getRequest());
        String userId = resolveUserIdByToken(token);
        return SseClient.instance().subscribe(userId);
    }

    /** 与 AuthWebFilter 一致：Authorization 头（Bearer 前缀可选）优先，其次 query 参数 token */
    private static String resolveToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (StrUtil.isNotBlank(auth)) {
            return auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        }
        return request.getQueryParams().getFirst("token");
    }

    /** 解析 token 载荷中的账号并派生 userId（MD5(username)，与登录侧一致） */
    private String resolveUserIdByToken(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        return accountProvider.buildRequestInfoByToken(token)
                .map(RequestInfoBO::getUserInfo)
                .map(RequestInfoBO.UserInfo::getUserId)
                .orElse(null);
    }
}
