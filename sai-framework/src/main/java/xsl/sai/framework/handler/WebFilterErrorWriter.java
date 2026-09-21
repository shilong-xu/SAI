package xsl.sai.framework.handler;

import cn.hutool.json.JSONUtil;
import xsl.sai.framework.enums.CodeEnum;
import xsl.sai.framework.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * WebFilter 阶段响应写入工具。
 *
 * <p>{@code @RestControllerAdvice} 只能捕获 Controller 层异常，WebFilter 中
 *   未登录等需直接写 JSON 响应，保持与 {@link Result} 协议一致。
 *
 * <p>HTTP 状态统一返回 200，业务语义通过 {@code Result.code} 表达，避免前端双套判定。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Slf4j
public final class WebFilterErrorWriter {

    private WebFilterErrorWriter() {}

    /** 把 {@link CodeEnum} 序列化为标准 JSON 响应 */
    public static Mono<Void> write(ServerWebExchange exchange, CodeEnum codeEnum) {
        Result body = Result.error(codeEnum.getCode(), codeEnum.getMessage());
        return write(exchange, HttpStatus.OK, body);
    }

    /** 写入任意 Result 响应（HTTP 状态固定 200） */
    public static Mono<Void> write(ServerWebExchange exchange, Result body) {
        return write(exchange, HttpStatus.OK, body);
    }

    /** 写入任意 Result 响应（自定义 HTTP 状态） */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, Result body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Cache-Control", "no-store");

        byte[] bytes = JSONUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
