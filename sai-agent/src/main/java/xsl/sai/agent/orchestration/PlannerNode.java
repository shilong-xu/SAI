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
 * 编排节点（一）：规划器。
 * 由 Spring AI Alibaba 的 ChatClient 承担，将用户请求拆解为清晰的执行步骤（仅规划、不执行）。
 *
 * <p>{@link AsyncNodeAction}：{@code apply} 返回 CompletableFuture，内部响应式调用 LLM，不阻塞。
 */
@Slf4j
public class PlannerNode implements AsyncNodeAction {

    private final ChatClient chatClient;

    public PlannerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String input = state.value("user_input", "");
        String analysis = state.value("analysis", "");
        String conversationId = state.value("conversation_id", "");
        log.info("[编排·planner·开始] sessionId --> {}, input --> {}", conversationId,
                AgentTraceMiddleware.clip(input, 400));
        return SaaNodeSupport.safeCall(chatClient,
                """
                        你是一个任务规划器。请基于下面的需求分析，将用户请求拆解为清晰的执行步骤（仅规划，不要执行）。
                        """
                        + (!analysis.isBlank() ? "【需求分析】\n" + analysis + "\n\n" : "")
                        + "用户请求：\n" + input)
                .map(plan -> {
                    log.info("[编排·planner·结束] sessionId --> {}, len --> {}", conversationId, plan.length());
                    return Map.<String, Object>of("plan", plan);
                })
                .toFuture();
    }
}
