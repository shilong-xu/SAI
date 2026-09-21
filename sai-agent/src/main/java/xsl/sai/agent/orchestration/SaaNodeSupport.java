package xsl.sai.agent.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 编排节点公共能力：收敛 analyzer / planner / summarizer 三个 SAA 节点中重复的 LLM 调用逻辑。
 *
 * <p><b>纯流式、零阻塞</b>：基于 Spring AI 的 {@code ChatClient.stream().content()} 收集 token，
 * 通过响应式 {@code timeout} 在超时（或异常）时降级为空白串，使流水线继续推进而非卡死。
 * 全程不使用 {@code .block()}，节点以 {@code AsyncNodeAction}（返回 CompletableFuture）方式接入 SAA StateGraph。
 */
@Slf4j
public final class SaaNodeSupport {

    /** 单次 SAA ChatClient 节点调用上限，与 AgentScope 节点 STEP_TIMEOUT 对齐 */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private SaaNodeSupport() {
    }

    /**
     * 带超时的安全 LLM 调用（响应式，不阻塞）。
     *
     * @return 模型输出文本；超时或异常时降级为空白串
     */
    public static Mono<String> safeCall(ChatClient client, String prompt) {
        return client.prompt()
                .user(prompt)
                .stream()
                .content()
                .collectList()
                .map(tokens -> (tokens == null || tokens.isEmpty()) ? "" : String.join("", tokens))
                .timeout(CALL_TIMEOUT, Mono.defer(() -> {
                    log.warn("[Orchestration] ChatClient 调用超时（{}s），降级为空串", CALL_TIMEOUT.getSeconds());
                    return Mono.just("");
                }))
                .onErrorResume(e -> {
                    log.warn("[Orchestration] ChatClient 调用失败（{}s）: {}", CALL_TIMEOUT.getSeconds(), e.getMessage());
                    return Mono.just("");
                });
    }
}
