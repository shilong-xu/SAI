package xsl.sai.framework.context;

import xsl.sai.framework.pojo.bo.RequestInfoBO;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 当前请求的用户上下文（基于 Reactor Context 实现）
 *
 * <p><b>为什么不用 {@code TransmittableThreadLocal}？</b>
 * <p>本项目基于 Spring WebFlux（响应式），{@code Mono}/{@code Flux} 会在多个 worker
 *    线程上调度执行，TTL 会在跨线程时丢失，导致 controller 读到 {@code null}。
 *    Reactor 的 {@link Context} 是为响应式设计的"线程安全"上下文，会沿 {@code Mono} 链
 *    自动向下游传递（{@code publishOn}/{@code subscribeOn} 不会丢），是正确做法。
 *
 * <p><b>使用约定</b>：
 * <ul>
 *   <li>{@code AuthWebFilter} 在校验通过后用 {@link #setInContext(RequestInfoBO)} 写入到 {@code Mono} 链</li>
 *   <li>业务代码用 {@link #get(ContextView)} 读取</li>
 *   <li>读取必须发生在响应式链上（拿到 {@link ContextView}），不能脱离 Reactor 调用</li>
 * </ul>
 *
 * @author XSL (移植自 xsl-framework，适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
public final class AuthContext {

    /**
     * Reactor Context 中的 key（私有，避免外部污染）
     */
    private static final Object KEY = new Object();

    private AuthContext() {
    }

    /**
     * 构造一个包含 {@link RequestInfoBO} 的 Reactor Context 片段
     *
     * <p>典型用法：
     * <pre>{@code
     * return chain.filter(exchange)
     *         .contextWrite(AuthContext.setInContext(requestInfo));
     * }</pre>
     *
     * @param requestInfoBO 当前请求的 BO（允许为 null，但下游读出来还是 null）
     * @return 可用于 {@code contextWrite} 的 Context
     */
    public static Context setInContext(RequestInfoBO requestInfoBO) {
        return Context.of(KEY, requestInfoBO);
    }

    /**
     * 从 {@link ContextView} 中读取当前请求的 {@link RequestInfoBO}
     *
     * <p>未携带或 BO 为 null 时返回 null（不会抛错）。
     */
    public static RequestInfoBO get(ContextView view) {
        if (view == null) {
            return null;
        }
        return (RequestInfoBO) view.getOrEmpty(KEY)
                .filter(o -> o instanceof RequestInfoBO).orElse(null);
    }

    /**
     * 从 {@link ContextView} 中读取；不存在时返回空 BO（字段全为 null）
     *
     * <p>仅用于"容忍空上下文"的兜底场景（如日志初始化）。
     */
    public static RequestInfoBO getOrEmpty(ContextView view) {
        RequestInfoBO bo = get(view);
        return bo != null ? bo : new RequestInfoBO();
    }

    /**
     * 判断当前上下文是否已注入 RequestInfoBO
     */
    public static boolean has(ContextView view) {
        return get(view) != null;
    }
}
