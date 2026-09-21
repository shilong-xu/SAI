package xsl.sai.agent.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Mono;
import xsl.sai.agent.hook.AgentTraceMiddleware;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 编排节点（零）：需求分析。
 * 在规划之前先解析用户意图、目标、约束与成功标准，为后续「规划」与「校验」提供统一基准。
 *
 * <p>本节点为 {@link AsyncNodeAction}：{@code apply} 返回 {@link CompletableFuture}，
 * 内部以响应式 {@link Mono} 驱动 LLM 调用（{@link SaaNodeSupport#safeCall}），全程不阻塞当前线程。
 */
@Slf4j
public class AnalyzerNode implements AsyncNodeAction {

    private final ChatClient chatClient;

    public AnalyzerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String input = state.value("user_input", "");
        String conversationId = state.value("conversation_id", "");
        log.info("[编排·analyzer·开始] sessionId --> {}, input --> {}", conversationId,
                AgentTraceMiddleware.clip(input, 400));
        return SaaNodeSupport.safeCall(chatClient,
                """
                        你是一名需求分析专家。请解析下面的用户请求，输出：
                         1) 核心目标；
                         2) 隐含约束与假设；
                         3) 所需信息 / 子任务；
                         4) 成功标准。

                        只做分析，不要执行。
                        用户请求：
                        """
                        + input)
                .map(analysis -> {
                    log.info("[编排·analyzer·结束] sessionId --> {}, len --> {}", conversationId, analysis.length());
                    return Map.<String, Object>of("analysis", analysis);
                })
                .toFuture();
    }
}
