package xsl.sai.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记录请求用容器
 * &#064; 2026/1/25
 * &#064; XSL
 *
 */
public class HttpRequestContext {

    private static final TransmittableThreadLocal<RequestInfo> CONTEXT = new TransmittableThreadLocal<>();

    public static void set(RequestInfo context) {
        CONTEXT.set(context);
    }

    public static RequestInfo get() {
        return CONTEXT.get();
    }

    public static void remove() {
        CONTEXT.remove();
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestInfo {

        /**
         * 客户端请求 IP
         */
        private String ip;

        /**
         * 请求链路ID
         */
        private String traceId;

        /**
         * 用户请求时间
         */
        private String timestamp;

    }


}
