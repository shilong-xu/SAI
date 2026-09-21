package xsl.sai.agent.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;
import xsl.sai.agent.hook.AgentTraceMiddleware;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j

/**
 * 官方集成范式落地：以「AgentScope(HarnessAgent) 为智能体核心、Spring AI Alibaba Graph 为工作流引擎」，
 * 把 HarnessAgent 封装成 SAA Graph 的一个节点。本类与官方
 * {@code com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent} 同构（instruction 模板 + outputKey + session），
 * 复用了官方设计思路，但底层直接走本项目 {@link AgentAI}（即 HarnessAgent），因此完整保留
 * 记忆 / 工具 / 技能 / 计划模式 / 文件上传等 HarnessAgent 能力。
 *
 * <p><b>本节点为 {@link AsyncNodeAction}</b>：{@code apply} 返回 {@link CompletableFuture}，
 * 内部以响应式方式驱动 HarnessAgent（{@link AgentAI#process} 返回 {@code Flux<String>}），
 * 通过 {@code .collectList().timeout()} 收口（响应式超时，非 {@code .block()}），全程不阻塞当前线程。
 *
 * <p>instruction 支持 {@code {{stateKey}}} 占位符，运行时从 OverAllState 取值渲染后作为给智能体的消息；
 * 结果写入 outputKey。sessionSuffix 用于会话隔离（如 "-critic" 走独立临时会话，不污染真实会话）。
 */
public class AgentScopeNode implements AsyncNodeAction {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    // 防御：渲染后若仍有未替换的占位符（对应 state key 缺失），整段剔除，避免把模板原文透传给模型
    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");
    protected static final Duration STEP_TIMEOUT = Duration.ofSeconds(120);

    // 会话后缀 -> 中文节点名，用于统一日志标识
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "", "核心", "-critic", "评审", "-reviser", "修订", "-validator", "校验",
            "-reviewer", "评审/校验");

    protected final AgentAI agentAI;
    private final String instructionTemplate;
    private final String outputKey;
    private final String sessionSuffix;

    public AgentScopeNode(AgentAI agentAI, String instructionTemplate,
                          String outputKey, String sessionSuffix) {
        this.agentAI = agentAI;
        this.instructionTemplate = instructionTemplate;
        this.outputKey = outputKey;
        this.sessionSuffix = (sessionSuffix == null) ? "" : sessionSuffix;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String userId = state.value("user_id", "");
        String conversationId = state.value("conversation_id", "");
        // 会话隔离：核心节点用真实会话，评审/修订/校验走各自独立临时会话
        String sessionId = conversationId + sessionSuffix;
        String role = ROLE_NAMES.getOrDefault(sessionSuffix,
                sessionSuffix.isEmpty() ? "核心" : sessionSuffix.substring(1));

        String message = render(state, instructionTemplate);
        if (message.isBlank()) {
            message = state.value("user_input", "");
        }

        return runAgent(userId, sessionId, role, message)
                .map(chunks -> {
                    String result = (chunks == null || chunks.isEmpty()) ? "" : String.join("", chunks);
                    return Map.<String, Object>of(outputKey, result);
                })
                .onErrorResume(e -> {
                    log.warn("[编排·{}·异常] sessionId --> {} error --> {}", role, sessionId, e.getMessage());
                    return Mono.just(Map.<String, Object>of(outputKey, ""));
                })
                .toFuture();
    }

    /**
     * 响应式调用 HarnessAgent（AgentAI）执行，并统一打印「开始 / 结束 / 异常」日志。
     * 返回 {@link Mono<List<String>>}：以 {@code .collectList().timeout()} 收口（响应式超时），不阻塞。
     */
    protected Mono<List<String>> runAgent(String userId, String sessionId, String role, String message) {
        log.info("[编排·{}·开始] sessionId --> {}, msg --> {}", role, sessionId,
                AgentTraceMiddleware.clip(message, 400));
        return agentAI.process(userId, sessionId, message)
                .collectList()
                .timeout(STEP_TIMEOUT, Mono.defer(() -> {
                    log.warn("[编排·{}·超时] sessionId --> {} 超过 {}s，降级为空", role, sessionId, STEP_TIMEOUT.getSeconds());
                    return Mono.just(List.of());
                }))
                .doOnNext(chunks -> {
                    String result = (chunks == null || chunks.isEmpty()) ? "" : String.join("", chunks);
                    log.info("[编排·{}·结束] sessionId --> {}, len --> {}", role, sessionId, result.length());
                })
                .onErrorResume(e -> {
                    log.warn("[编排·{}·异常] sessionId --> {} error --> {}", role, sessionId, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * 将 instruction 模板中的 {{stateKey}} 替换为 OverAllState 中对应值（缺省为空串）。
     */
    protected String render(OverAllState state, String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = state.value(key, "");
            String val = v.toString();
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        // 防御：清除任何未被替换的残留占位符（如对应 state key 当时尚未产出）
        return LEFTOVER.matcher(sb.toString()).replaceAll("").trim();
    }
}
