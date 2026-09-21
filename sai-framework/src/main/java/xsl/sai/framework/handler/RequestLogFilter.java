package xsl.sai.framework.handler;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.Nonnull;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.LogClient;
import xsl.sai.framework.context.HttpRequestContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RequestLogFilter implements WebFilter {

    private final LogClient logClient;
    private static final int MAX_BODY_LENGTH = 1024 * 100;

    public RequestLogFilter(LogClient logClient) {
        this.logClient = logClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        String path = request.getPath().toString();
        String method = request.getMethod().name();
        DataBufferFactory bufferFactory = response.bufferFactory();

        String traceId = UUID.randomUUID().toString().replace("-", "");
        String clientIp = getClientIp(request);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        HttpRequestContext.set(HttpRequestContext.RequestInfo.builder()
                .traceId(traceId).ip(clientIp).timestamp(timestamp).build());

        Map<String, Object> requestLog = new HashMap<>();
        requestLog.put("ip", clientIp);
        requestLog.put("timestamp", timestamp);
        requestLog.put("path", path);
        requestLog.put("method", method);
        requestLog.put("queryParams", request.getQueryParams());

        return recordRequestBodyAndDecorate(request, requestLog, bufferFactory)
                .flatMap(decoratedRequest -> {
                    ServerWebExchange newExchange = exchange.mutate().request(decoratedRequest).build();
                    return chain.filter(newExchange)
                            .doFinally(s -> {
                                try {
                                    Map<String, Object> responseLog = new HashMap<>();
                                    responseLog.put("path", path);
                                    responseLog.put("method", method);
                                    responseLog.put("status", response.getStatusCode() != null ? response.getStatusCode().value() : 0);
                                    responseLog.put("duration", System.currentTimeMillis() - startTime + "ms");
                                    MediaType contentType = response.getHeaders().getContentType();
                                    responseLog.put("isStream", contentType != null &&
                                            (contentType.toString().contains("text/event-stream") ||
                                                    contentType.toString().contains("application/stream+json")));
                                    logClient.log("【RESPONSE】" + JSONUtil.toJsonStr(responseLog)).subscribe();
                                } catch (Exception e) {
                                    // 忽略日志记录异常，不影响响应
                                } finally {
                                    HttpRequestContext.remove();
                                }
                            });
                });
    }

    private Mono<ServerHttpRequest> recordRequestBodyAndDecorate(ServerHttpRequest request, Map<String, Object> requestLog, DataBufferFactory bufferFactory) {
        MediaType contentType = request.getHeaders().getContentType();
        // multipart/form-data 不能消费 body（会破坏边界流，导致下游解析 "Could not find first boundary"），
        // 直接透传原始请求，仅记录元数据，避免把文件二进制写入日志。
        if (contentType != null && contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)) {
            requestLog.put("body", "(multipart/form-data, 已跳过记录)");
            return logClient.log("【REQUEST】" + JSONUtil.toJsonStr(requestLog)).thenReturn(request);
        }
        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(bufferFactory.allocateBuffer(0))
                .flatMap(buffer -> {
                    byte[] bodyBytes = new byte[buffer.readableByteCount()];
                    buffer.read(bodyBytes);
                    DataBufferUtils.release(buffer);

                    String body = new String(bodyBytes, StandardCharsets.UTF_8);
                    if (!body.isEmpty()) {
                        try {
                            requestLog.put("body", JSONUtil.parse(body));
                        } catch (Exception e) {
                            requestLog.put("body", truncate(body));
                        }
                    }

                    Mono<Void> logMono = logClient.log("【REQUEST】" + JSONUtil.toJsonStr(requestLog));
                    if (bodyBytes.length == 0) {
                        return logMono.thenReturn(request);
                    }

                    DataBuffer newBuffer = bufferFactory.wrap(bodyBytes);
                    return logMono.thenReturn(new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(newBuffer);
                        }
                    });
                });
    }

    private String truncate(String str) {
        return str.length() > MAX_BODY_LENGTH ? str.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : str;
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeaders().getFirst("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeaders().getFirst("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddress() != null ? request.getRemoteAddress().getHostString() : "unknown";
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }

}