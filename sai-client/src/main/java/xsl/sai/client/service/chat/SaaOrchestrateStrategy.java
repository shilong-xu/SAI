package xsl.sai.client.service.chat;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;
import xsl.sai.agent.orchestration.WorkflowService;
import xsl.sai.client.manager.ChatStreamRegistry;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;
import xsl.sai.framework.result.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * SAA 编排策略：经 Spring AI Alibaba StateGraph 静态流水线
 * （需求分析→规划→核心→评审校验→修订→总结）产出回复。
 * 每节点完成吐一条 pipelineStep 进「流水线面板」，仅 summarizer（总结）节点额外推送 reply 进气泡。
 * 因 graph 内部多轮 LLM 阻塞，流式放在 boundedElastic 调度器执行。
 */
@Slf4j
@Service
@Order(1)
public class SaaOrchestrateStrategy extends AbstractChatStrategy {

    /** 面向用户的节点：中途停止时仅以这些节点的产出作为兜底落库，过滤内部过程文本 */
    private static final Set<String> USER_FACING_NODES = Set.of(
            "core_agent", "reviser", "reviewer", "summarizer");

    private final WorkflowService workflowService;

    public SaaOrchestrateStrategy(WorkflowService workflowService,
                                  MessageService messageService,
                                  ConversationService conversationService,
                                  ChatStreamRegistry chatStreamRegistry) {
        super(messageService, conversationService, chatStreamRegistry);
        this.workflowService = workflowService;
    }

    @Override
    public boolean supports(ChatDTO dto) {
        return dto.isSaaMode();
    }

    @Override
    protected String modeName() {
        return "SAA";
    }

    @Override
    protected reactor.core.scheduler.Scheduler streamScheduler() {
        return reactor.core.scheduler.Schedulers.boundedElastic();
    }

    @Override
    protected Flux<Result> buildStream(ChatContext ctx, ReplyBuffer reply) {
        return workflowService.streamOrchestrate(ctx.userId(), ctx.sessionId(), ctx.message())
                .map(step -> {
                    // 节点开始占位事件（started=true，text 为空）：仅用于让前端立即显示「该节点进行中」，
                    // 不参与任何落库汇总（无文本）。
                    if (step.started()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("pipelineStep", Map.of(
                                "node", step.node(),
                                "label", step.label(),
                                "text", "",
                                "started", true));
                        return Result.success(data);
                    }
                    // 中断兜底：仅汇总「面向用户」的节点产出（核心智能体 / 修订 / 评审 / 总结），
                    // 丢弃 analyzer(需求分析) / planner(规划) 等内部过程文本，
                    // 避免用户中途停止时把一堆开发者视角内容存进聊天记录。
                    if (USER_FACING_NODES.contains(step.node())) {
                        reply.appendFallback(step.text());
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("pipelineStep", Map.of(
                            "node", step.node(),
                            "label", step.label(),
                            "text", step.text(),
                            "started", false));
                    if ("summarizer".equals(step.node())) {
                        reply.appendAnswer(step.text());
                        data.put("reply", step.text());
                    }
                    return Result.success(data);
                });
    }

    @Override
    protected String resolveReply(ReplyBuffer reply) {
        // SAA 未跑到总结节点（如用户中途停止）时，回退到已产出的各步文本，保证对话数据落库
        String answer = reply.answer();
        return StrUtil.isNotBlank(answer) ? answer : reply.fallback();
    }
}
