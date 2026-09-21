package xsl.sai.agent.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 评审节点：在独立临时会话（sessionSuffix = "-reviewer"）中审阅「核心智能体产出」或「上一轮修订稿」，
 * 指出事实错误、逻辑漏洞、遗漏或未满足用户需求之处，并给出具体、可执行的修改建议，
 * 供后续 reviser（修订）节点直接采纳。
 *
 * <p>支持自检回环：首轮审阅 {@code core_result}（核心智能体产出）；当编排流水线把本节点带回时
 * （{@code revision_round > 0}），state 中已存在上一轮修订稿（{@code revision}），本节点改用
 * {@link #ITERATE_TEMPLATE} 审阅修订稿。回环次数由配置项 {@code sai.orchestration.self-check-rounds} 控制。
 *
 * <p>原 critic(评审) 与 validator(校验) 两个 HarnessAgent 节点已合并为本节点，重量级调用由 4 次降为 3 次。
 */
public class ReviewerNode extends AgentScopeNode {

    /** 首轮模板：审阅核心智能体产出 */
    private static final String FIRST_ROUND_TEMPLATE =
            """
            你是严格的评审专家。请审阅下面的「核心智能体产出」，指出事实错误、逻辑漏洞、遗漏或未满足用户需求之处，\
            给出具体、可执行的修改建议（只给审阅意见，不要直接重写）。

            【需求分析】
            {{analysis}}

            【执行计划】
            {{plan}}

            【用户请求】
            {{user_input}}

            【核心智能体产出】
            {{core_result}}""";

    /** 迭代模板：审阅上一轮修订稿（自检回环时使用） */
    private static final String ITERATE_TEMPLATE =
            """
            你是严格的评审专家。请审阅下面的「上一轮修订稿」，指出事实错误、逻辑漏洞、遗漏或未满足用户需求之处，\
            给出具体、可执行的修改建议（只给审阅意见，不要直接重写）。

            【用户请求】
            {{user_input}}

            【核心智能体产出】
            {{core_result}}

            【上一轮修订稿】
            {{revision}}""";

    public ReviewerNode(AgentAI agentAI) {
        // instructionTemplate / outputKey 仅满足父类构造；实际逻辑在 apply() 中重写
        super(agentAI, FIRST_ROUND_TEMPLATE, "critic_result", "-reviewer");
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        // 是否已有上一轮修订稿：有则用迭代模板审阅修订稿，否则用首轮模板审阅核心产出
        String prevRevision = state.value("revision", "");
        String template = !prevRevision.isBlank() ? ITERATE_TEMPLATE : FIRST_ROUND_TEMPLATE;

        String userId = state.value("user_id", "");
        String conversationId = state.value("conversation_id", "");
        String sessionId = conversationId + "-reviewer";

        String message = render(state, template);

        return runAgent(userId, sessionId, "评审", message)
                .map(chunks -> {
                    String result = (chunks == null || chunks.isEmpty()) ? "" : String.join("", chunks);
                    return Map.<String, Object>of("critic_result", result);
                })
                .toFuture();
    }
}
