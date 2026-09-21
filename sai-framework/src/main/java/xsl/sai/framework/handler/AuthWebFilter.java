package xsl.sai.framework.handler;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import xsl.sai.framework.annotation.NoAuth;
import xsl.sai.framework.config.AccountProvider;
import xsl.sai.framework.context.AuthContext;
import xsl.sai.framework.enums.CodeEnum;
import xsl.sai.framework.holder.UserHolder;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 令牌校验过滤器（最高优先级）。
 *
 * <p>个人工具方案：<b>无用户概念、无状态 JWT（胡图 JWTUtil 签名）</b>。
 * 登录成功后后端用 {@code JWTUtil} 生成自包含 JWT（载荷含账号与用户姓名）返回，
 * 前端后续请求在 {@code Authorization} 头携带该令牌，
 * 本过滤器用 {@code JWTUtil} 验签 + 校验过期，通过即放行，并解析出当前用户写入上下文。
 *
 * <p><b>鉴权模型（注解驱动）</b>：默认所有 controller 端点都需令牌校验，
 * 仅当处理器（类或方法）标注 {@link NoAuth} 时才放行；
 * 非 {@link HandlerMethod} 的请求（静态资源、错误页等）一律直接放行，保证登录页可访问。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuthWebFilter implements WebFilter {

    private final AccountProvider accountProvider;
    private final RequestMappingHandlerMapping handlerMapping;

    @Override
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        return handlerMapping.getHandler(exchange)
                .defaultIfEmpty(new Object())
                .flatMap(handler -> {
                    if (isNoAuth(handler)) {
                        return chain.filter(exchange);
                    }
                    return doAuth(exchange, chain);
                });
    }

    /**
     * 无需鉴权的判定：
     * <ul>
     *   <li>非 {@link HandlerMethod} 处理器（静态资源、错误页等）→ 直接放行；</li>
     *   <li>标注了 {@link NoAuth} 注解的接口/方法 → 放行；</li>
     * </ul>
     * 其余所有 controller 端点默认均需令牌校验。
     */
    private boolean isNoAuth(Object handler) {
        if (handler instanceof HandlerMethod hm) {
            return hm.getMethodAnnotation(NoAuth.class) != null
                    || hm.getBeanType().getAnnotation(NoAuth.class) != null;
        }
        // 非 HandlerMethod（静态资源等）一律放行
        return true;
    }

    /**
     * 鉴权：校验 token 合法性，通过后将当前用户信息写入 Reactor 上下文，
     * 供下游 controller / service 通过 {@link UserHolder} 响应式取用。
     */
    private Mono<Void> doAuth(ServerWebExchange exchange, WebFilterChain chain) {
        String token = resolveToken(exchange);
        if (StrUtil.isBlank(token)) {
            return WebFilterErrorWriter.write(exchange, CodeEnum.NOT_LOGIN);
        }
        if (!accountProvider.isValidToken(token)) {
            return WebFilterErrorWriter.write(exchange, CodeEnum.TOKEN_INVALID);
        }
        return accountProvider.buildRequestInfoByToken(token)
                .map(bo -> {
                    fillRequestMeta(exchange, bo);
                    return chain.filter(exchange)
                            .contextWrite(AuthContext.setInContext(bo))
                            .doOnSubscribe(s -> UserHolder.setFallback(bo))
                            .doFinally(sig -> UserHolder.clearFallback());
                })
                .orElseGet(() -> WebFilterErrorWriter.write(exchange, CodeEnum.TOKEN_INVALID));
    }

    /**
     * 填充请求元信息，保证 requestId / requestTime 不为空：
     * <ul>
     *   <li>requestId：优先取请求头 {@code X-Request-Id}（便于网关/前端串联链路），缺省生成 UUID；</li>
     *   <li>requestTime：当前时间（yyyy-MM-dd HH:mm:ss.SSS）。</li>
     * </ul>
     */
    private void fillRequestMeta(ServerWebExchange exchange, xsl.sai.framework.pojo.bo.RequestInfoBO bo) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        if (StrUtil.isBlank(requestId)) {
            requestId = IdUtil.fastSimpleUUID();
        }
        bo.setRequestId(requestId);
        bo.setRequestTime(DateUtil.format(DateUtil.date(), DatePattern.NORM_DATETIME_MS_PATTERN));
    }

    /** 从 Authorization 头（支持 Bearer 前缀）或 query 参数中取 token */
    private String resolveToken(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (StrUtil.isNotBlank(auth)) {
            return auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        }
        return exchange.getRequest().getQueryParams().getFirst("token");
    }
}
