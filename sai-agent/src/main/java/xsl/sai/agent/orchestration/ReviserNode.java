package xsl.sai.agent.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 修订节点：在独立临时会话（sessionSuffix = "-reviser"）中依据「评审意见（critic_result）」，
 * 对「核心智能体产出」或「上一轮修订稿」进行修订，输出完整、可直接交付的修订稿。
 *
 * <p>支持迭代修订（自检回环）：编排流水线在 reviser 完成后，由 WorkflowService 的条件边
 * 按 {@code revision_round} 与 {@code self-check-rounds} 决定是否回到 reviewer 再走一轮。
 * 当本节点被再次执行时，state 中已存在上一轮修订稿（revision），本节点改用
 * {@link #ITERATE_TEMPLATE} 结合最新评审意见继续修订；每次执行 {@code revision_round +1}。
 * （评审与校验已合并为 reviewer 节点，统一写入 critic_result，故修订只依赖 critic_result。）
 *
 * <p>{@link AsyncNodeAction}：{@code apply} 返回 CompletableFuture，内部响应式驱动 HarnessAgent，不阻塞。
 */
public class ReviserNode extends AgentScopeNode {

    /** 首轮模板：直接基于核心产出 + 评审意见修订 */
    private static final String FIRST_ROUND_TEMPLATE =
            """
                    你是修订专家。请依据「评审意见」对「核心智能体产出」进行修订，\
                    输出一段完整、可直接交付的修订稿（不要保留审阅痕迹）。
                    
                    【用户请求】
                    {{user_input}}
                    
                    【核心智能体产出】
                    {{core_result}}
                    
                    【评审意见】
                    {{critic_result}}""";

    /** 迭代模板：基于上一轮修订稿 + 最新评审意见继续修订（评审/校验已合一，无需单独校验意见） */
    private static final String ITERATE_TEMPLATE =
            """
                    你是修订专家。上一版修订稿经评审后仍需改进，请结合最新评审意见继续修订，\
                    输出一段完整、可直接交付的修订稿（不要保留审阅痕迹）。
                    
                    【用户请求】
                    {{user_input}}
                    
                    【核心智能体产出】
                    {{core_result}}
                    
                    【最新评审意见】
                    {{critic_result}}
                    
                    【上一轮修订稿】
                    {{revision}}""";

    public ReviserNode(AgentAI agentAI) {
        super(agentAI, FIRST_ROUND_TEMPLATE, "revision", "-reviser");
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        // 是否已有上一轮修订稿：有则用迭代模板，否则用首轮模板
        String prevRevision = state.value("revision", "");
        boolean hasPrev = !prevRevision.isBlank();
        String template = hasPrev ? ITERATE_TEMPLATE : FIRST_ROUND_TEMPLATE;

        String userId = state.value("user_id", "");
        String conversationId = state.value("conversation_id", "");
        String sessionId = conversationId + "-reviser";

        String message = render(state, template);
        if (message.isBlank()) {
            message = state.value("user_input", "");
        }

        int round = state.value("revision_round", 0);
        return runAgent(userId, sessionId, "修订", message)
                .map(chunks -> {
                    String result = (chunks == null || chunks.isEmpty()) ? "" : String.join("", chunks);
                    return Map.<String, Object>of("revision", result, "revision_round", round + 1);
                })
                .toFuture();
    }
}
