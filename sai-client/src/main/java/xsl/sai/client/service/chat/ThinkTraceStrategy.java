package xsl.sai.client.service.chat;

import io.agentscope.core.message.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;
import xsl.sai.agent.AgentAI;
import xsl.sai.client.manager.ChatStreamRegistry;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;
import xsl.sai.framework.pojo.vo.LoopStep;
import xsl.sai.framework.result.Result;

/**
 * 思考追踪策略（原 AgentLoop 模式）：驱动 AgentScope 核心智能体跑 ReAct 循环，
 * 以 SSE 逐条流式返回每步 {@link LoopStep}（推理 / 调工具 / 观察 / 最终回答），
 * 仅把 ANSWER 步骤的文本累积为最终答案用于落库。
 */
@Slf4j
@Service
@Order(2)
public class ThinkTraceStrategy extends AbstractChatStrategy {

    private final AgentAI agentAI;

    public ThinkTraceStrategy(AgentAI agentAI,
                              MessageService messageService,
                              ConversationService conversationService,
                              ChatStreamRegistry chatStreamRegistry) {
        super(messageService, conversationService, chatStreamRegistry);
        this.agentAI = agentAI;
    }

    @Override
    public boolean supports(ChatDTO dto) {
        // 思考追踪与 SAA 互斥：两者同时开启时让位于 SAA，避免分派结果依赖 bean 注册顺序（不确定行为）
        return dto.isLoopMode() && !dto.isSaaMode();
    }

    @Override
    protected String modeName() {
        return "Orchestrate";
    }

    @Override
    protected Flux<Result> buildStream(ChatContext ctx, ReplyBuffer reply) {
        return agentAI.processLoop(ctx.userId(), ctx.sessionId(), ctx.message())
                .doOnNext(step -> {
                    if ("ANSWER".equals(step.getType())) {
                        // 仅在 ANSWER 步骤累积最终回答到落库缓冲；不剔除纯空白分片，
                        // 否则列表/标题之间的换行会被吞掉，导致历史消息的 markdown 渲染错位
                        // （h2 吞掉后续列表行等）。null 分片跳过，空串/纯空白透传给前端累积逻辑。
                        if (step.getContent() != null) {
                            reply.appendAnswer(step.getContent());
                        }
                    }
                })
                .map(Result::success);
    }
}
