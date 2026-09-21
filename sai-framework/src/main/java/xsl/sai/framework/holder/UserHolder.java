package xsl.sai.framework.holder;

import xsl.sai.framework.context.AuthContext;
import xsl.sai.framework.pojo.bo.RequestInfoBO;
import reactor.util.context.ContextView;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 获取当前登录用户信息
 *
 * <p><b>响应式适配</b>：本项目基于 Spring WebFlux（响应式），上下文从
 *    {@link AuthContext} 提供的 {@link ContextView} 中读取，跨线程安全。
 *
 * <p>所有"取当前用户字段"的方法均提供两个版本：
 * <ul>
 *   <li><b>响应式版</b>（推荐）：{@code getXxx(ContextView)}，在 controller
 *       /service 的响应式链中通过 {@code Mono.deferContextual(view -> ...)} 拿到 view 后调用</li>
 *   <li><b>兼容版</b>（{@code @Deprecated}）：无参 {@code getXxx()}，在非响应式线程
 *       通过 {@link TransmittableThreadLocal} 兜底取数。
 *       <span style="color:#9b1b30">响应式链里请勿使用（TTL 在 worker 切换时会丢）</span></li>
 * </ul>
 *
 * <p>也提供 {@link #from(ContextView)} 一站式从 view 取 {@link RequestInfoBO}。
 *
 * @author XSL (移植自 xsl-framework，适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
public class UserHolder {

    /* ============================================================
     * 兜底 TTL：仅在响应式链"非 reactor 上下文"的极小概率场景下兜底
     * （业务代码不应再调用无参版本）
     * ============================================================ */

    private static final TransmittableThreadLocal<RequestInfoBO> FALLBACK_TL = new TransmittableThreadLocal<>();

    /**
     * 兜底写入：仅供 {@code AuthWebFilter} 在写完 Reactor Context 之外再写一次 TTL，
     * 让老调用点（无参 static）不至于完全失效。
     * <p>业务代码不要调用本方法。
     */
    public static void setFallback(RequestInfoBO bo) {
        if (bo == null) {
            FALLBACK_TL.remove();
        } else {
            FALLBACK_TL.set(bo);
        }
    }

    public static void clearFallback() {
        FALLBACK_TL.remove();
    }

    private static RequestInfoBO fallback() {
        return FALLBACK_TL.get();
    }

    /* ============================================================
     * 解析 view：业务推荐用响应式版（接收 view）
     * ============================================================ */

    /**
     * 从 {@link ContextView} 取 {@link RequestInfoBO}，并保证非 null
     */
    public static RequestInfoBO from(ContextView view) {
        return AuthContext.getOrEmpty(view);
    }

    /**
     * 从 {@link ContextView} 取 UserInfo 内部对象
     */
    public static RequestInfoBO.UserInfo userInfoFrom(ContextView view) {
        RequestInfoBO bo = from(view);
        RequestInfoBO.UserInfo userInfo = bo.getUserInfo();
        if (userInfo == null) {
            userInfo = new RequestInfoBO.UserInfo();
        }
        return userInfo;
    }

    /* ============================================================
     * 响应式版（推荐使用）
     * ============================================================ */

    public static String getRequestId(ContextView view) {
        return from(view).getRequestId();
    }

    public static String getRequestTime(ContextView view) {
        return from(view).getRequestTime();
    }

    public static String getUserId(ContextView view) {
        return userInfoFrom(view).getUserId();
    }

    public static String getIdentityId(ContextView view) {
        return userInfoFrom(view).getIdentityId();
    }

    public static String getUsername(ContextView view) {
        return userInfoFrom(view).getUsername();
    }

    public static String getDisplayName(ContextView view) {
        return userInfoFrom(view).getDisplayName();
    }

}
