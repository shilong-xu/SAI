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
 * 总结器节点：基于「核心智能体输出 + 审阅意见」生成给用户的最终回复。
 *
 * <p>{@link AsyncNodeAction}：{@code apply} 返回 CompletableFuture，内部响应式调用 LLM，不阻塞。
 */
@Slf4j
public class SummarizerNode implements AsyncNodeAction {

    private final ChatClient chatClient;

    public SummarizerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String coreResult = state.value("core_result", "");
        String revision = state.value("revision", "");
        String critic = state.value("critic_result", "");
        String validation = state.value("validation", "");
        String conversationId = state.value("conversation_id", "");

        // 优先使用经评审修订后的产出，缺失时降级到核心智能体原始输出
        String source = !revision.isBlank() ? revision : coreResult;
        if (source.isBlank()) {
            return CompletableFuture.completedFuture(Map.<String, Object>of("final_answer", ""));
        }

        log.info("[编排·summarizer·开始] sessionId --> {}, src --> {}", conversationId,
                AgentTraceMiddleware.clip(source, 400));

        String prompt = """
                你负责把智能体的工作成果整理成给用户的最终回复。
                要求：保留全部有效信息、结论与关键步骤；吸收审阅意见中的合理修改建议；
                去除冗长思考过程、内部调试信息与重复内容；用清晰的中文、分点或小标题组织；
                语气自然、像真人助手。不要编造未出现的事实。

                【待整理产出】
                %s
                %s%s"""
                .formatted(source,
                        !critic.isBlank() ? "【审阅意见】\n" + critic + "\n" : "",
                        !validation.isBlank() ? "【校验结论】\n" + validation + "\n" : "");

        return SaaNodeSupport.safeCall(chatClient, prompt)
                .map(answer -> {
                    log.info("[编排·summarizer·结束] sessionId --> {}, len --> {}", conversationId, answer.length());
                    return Map.<String, Object>of("final_answer", answer);
                })
                .toFuture();
    }
}
