package xsl.sai.agent.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 流程编排服务（Spring AI Alibaba Graph，顶层 orchestration）。
 *
 * <p>工作流（核心流水线）：
 * START → 需求分析(SAA) → 规划(SAA) → 核心智能体(AgentScope) → 评审/校验(AgentScope)
 *      → 修订(AgentScope) → 总结(SAA) → END。
 * 既体现「多步骤」编排，也体现「多智能体协作」：AgentScope 构建的核心 / 评审 / 修订 / 校验
 * 智能体与 SAA ChatClient 承担的分析 / 规划 / 总结智能体被 SAA 的 Graph 串成企业级流水线。
 *
 * <p><b>自检回环（可配置）</b>：评审(reviewer)→修订(reviser)完成后，按「修订轮数 revision_round」
 * 与配置项 {@code sai.orchestration.self-check-rounds}（即回环次数，默认 1）判断是否再回到 reviewer
 * 做下一轮「评审→修订」。回环次数 = N 时，reviewer 与 reviser 各执行 N+1 次、核心/总结各 1 次，
 * 通过多轮自评自改提升终稿质量；设为 0 则退化为线性流水线（reviewer/reviser 各 1 次）。
 *
 * <p><b>纯响应式、零阻塞</b>：所有节点均实现 {@code AsyncNodeAction}（{@code apply} 返回 CompletableFuture），
 * 内部以 {@code Mono/Flux} 驱动 LLM 与 AgentScope，不再使用 {@code .block()}。流式执行
 * （{@link #streamOrchestrate}）逐节点推回产出文本，异常时以纯响应式方式降级，不阻塞调用线程。
 */
@Slf4j
@Service
public class WorkflowService {

    private final CompiledGraph compiledGraph;
    /** 自检回环次数（即 reviewer↔reviser 之间的循环次数），由配置项 sai.orchestration.self-check-rounds 注入，默认 1 */
    private final int selfCheckRounds;

    public WorkflowService(AgentAI agentAI, ChatClient chatClient,
                           @Value("${sai.orchestration.self-check-rounds:1}") int selfCheckRounds) throws Exception {
        this.selfCheckRounds = Math.max(0, selfCheckRounds);
        KeyStrategyFactory ksf = new KeyStrategyFactoryBuilder()
                .addStrategy("user_input", KeyStrategy.REPLACE)
                .addStrategy("conversation_id", KeyStrategy.REPLACE)
                .addStrategy("user_id", KeyStrategy.REPLACE)
                .addStrategy("analysis", KeyStrategy.REPLACE)
                .addStrategy("plan", KeyStrategy.REPLACE)
                .addStrategy("core_result", KeyStrategy.REPLACE)
                .addStrategy("critic_result", KeyStrategy.REPLACE)
                .addStrategy("revision", KeyStrategy.REPLACE)
                .addStrategy("validation", KeyStrategy.REPLACE)
                .addStrategy("final_answer", KeyStrategy.REPLACE)
                .addStrategy("revision_round", KeyStrategy.REPLACE)
                .build();

        StateGraph graph = new StateGraph("sai-orchestration", ksf);
        // 所有节点均为 AsyncNodeAction（返回 CompletableFuture），由图以响应式方式驱动，零阻塞
        graph.addNode("analyzer", new AnalyzerNode(chatClient));
        graph.addNode("planner", new PlannerNode(chatClient));
        graph.addNode("core_agent", new CoreAgentNode(agentAI));
        graph.addNode("reviewer", new ReviewerNode(agentAI));
        graph.addNode("reviser", new ReviserNode(agentAI));
        graph.addNode("summarizer", new SummarizerNode(chatClient));

        // 需求分析(analyzer) 与 规划(planner) 互不依赖，从 START 并行扇出；
        // core_agent 通过 List 扇入等待两者都完成，省去一段顺序 LLM 调用延迟。
        // 评审/校验(reviewer) 评审核心产出并给出修订建议 → reviser 据此修订产出修订稿。
        // reviser 完成后进入【自检回环】：按已执行修订轮数(revision_round) 与 selfCheckRounds 决定
        // 是回到 reviewer 做下一轮「评审→修订」，还是进入总结节点（见 routeSelfCheck）。
        graph.addEdge(StateGraph.START, "analyzer");
        graph.addEdge(StateGraph.START, "planner");
        graph.addEdge(List.of("analyzer", "planner"), "core_agent");
        graph.addEdge("core_agent", "reviewer");
        graph.addEdge("reviewer", "reviser");
        graph.addConditionalEdges("reviser", this::routeSelfCheck,
                Map.of("loop", "reviewer", "done", "summarizer"));
        graph.addEdge("summarizer", StateGraph.END);

        CompiledGraph g;
        try {
            g = graph.compile();
        } catch (NullPointerException npe) {
            // 兼容 spring-ai-alibaba-graph 1.1.2.0 已知 bug：Edge.validate 对条件边按目标 id 分组时，
            // 条件 EdgeValue 的 id 为 null，会触发 NPE（"element cannot be mapped to a null key"）。
            // 该 NPE 不影响运行时（条件路由由 EdgeCondition 在运行时处理），且本项目当前无法升级到
            // 已修复的 1.1.2.3（与现有 spring-ai/zhipuai 版本不兼容），故在此绕过有缺陷的 validateGraph，
            // 直接构建 CompiledGraph，条件路由逻辑保持完整。库升级到修复版本后此分支将不再触发。
            g = buildCompiledGraph(graph);
        }
        this.compiledGraph = g;
        log.info("[Orchestration] SAA StateGraph 编排流程已编译就绪（评审/校验已合并，自检回环 {} 轮）", selfCheckRounds);
    }

    /**
     * 自检回环路由：reviser 完成后触发。读取已完成修订轮数（revision_round），
     * 若未超过配置上限 {@link #selfCheckRounds} 则回到 reviewer 继续「评审→修订」，
     * 否则进入总结节点。
     *
     * <p>返回字符串作为条件边的路由键：{@code "loop"} → reviewer，{@code "done"} → summarizer。
     */
    private CompletableFuture<String> routeSelfCheck(OverAllState state) {
        int round = state.value("revision_round", 0);
        return CompletableFuture.completedFuture(round <= selfCheckRounds ? "loop" : "done");
    }

    /**
     * 渐进（节点级）流式执行编排流程，逐节点把产出文本推回，便于前端实时渲染流水线进度。
     * 注意：受 Spring AI Alibaba StateGraph 节点契约限制，流式粒度为「节点完成」，
     * 而非 LLM token 级；节点内部的思考/工具调用不对外暴露中间 token。
     *
     * <p>为消除「节点内长 LLM 期间前端无反馈、观感卡死」的问题，每个节点在产出前先发一条
     * {@code started=true} 的占位事件（文本为空），让前端立即显示「该节点进行中」；节点产出
     * 时再发带文本的 {@link NodeStep}。心跳由上游 AbstractChatStrategy 统一合并，保证 90s
     * 网关超时内连接持续保活。</p>
     *
     * @return 每个非起止节点至少一条 {@link NodeStep}（节点名 + 中文标签 + 文本 + 是否占位）
     */
    public Flux<NodeStep> streamOrchestrate(String userId, String conversationId, String message) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("user_input", message);
        inputs.put("conversation_id", conversationId);
        inputs.put("user_id", userId);

        return compiledGraph.stream(inputs)
                .filter(no -> !no.isSTART() && !no.isEND())
                // 每个节点拆成两条事件：① 开始占位（立即反馈）② 产出文本（如有）
                .flatMap(no -> {
                    String node = no.node();
                    String label = NODE_LABEL.getOrDefault(node, node);
                    String key = NODE_KEY.getOrDefault(node, "final_answer");
                    String text = no.state().value(key, "");
                    if (text != null && !text.isBlank()) {
                        return Flux.just(NodeStep.start(node, label), NodeStep.of(node, label, text));
                    }
                    return Flux.just(NodeStep.start(node, label));
                })
                .filter(step -> step.started() || (step.text() != null && !step.text().isBlank()))
                // 防御：若 graph.stream() 在运行时踩到 SAA 1.1.2.0 的已知缺陷（如条件边 NPE），
                // 以纯响应式方式降级为整体返回（重新跑一次图，取总结节点产出），不阻塞调用线程。
                .onErrorResume(e -> {
                    log.warn("[Orchestration] graph.stream 失败，降级为整体返回: {}", e.getMessage());
                    return fallbackStep(inputs);
                });
    }

    /**
     * 纯响应式降级：重新跑一次图，取总结节点产出，避免阻塞式 invoke。
     */
    private Flux<NodeStep> fallbackStep(Map<String, Object> inputs) {
        return compiledGraph.stream(inputs)
                .filter(no -> "summarizer".equals(no.node()))
                .next()
                .map(no -> NodeStep.of("summarizer", "总结", no.state().value("final_answer", "")))
                .defaultIfEmpty(NodeStep.of("summarizer", "总结", ""))
                .onErrorResume(ex -> Mono.just(NodeStep.of("summarizer", "总结", "")))
                .flux();
    }

    /** 单个编排节点的产出（用于流式推送）。{@code started=true} 表示「节点开始」占位事件（text 为空），前端据此立即显示进度。 */
    public record NodeStep(String node, String label, String text, boolean started) {
        /** 节点开始占位事件：文本为空，仅用于让前端立即显示「该节点进行中」 */
        public static NodeStep start(String node, String label) {
            return new NodeStep(node, label, "", true);
        }

        /** 节点产出事件（含文本） */
        public static NodeStep of(String node, String label, String text) {
            return new NodeStep(node, label, text, false);
        }
    }

    private static final Map<String, String> NODE_KEY = Map.of(
            "analyzer", "analysis",
            "planner", "plan",
            "core_agent", "core_result",
            "reviewer", "critic_result",
            "reviser", "revision",
            "summarizer", "final_answer");

    private static final Map<String, String> NODE_LABEL = Map.of(
            "analyzer", "需求分析",
            "planner", "规划",
            "core_agent", "核心智能体",
            "reviewer", "评审",
            "reviser", "修订",
            "summarizer", "总结");


    /**
     * 绕过 spring-ai-alibaba-graph 1.1.2.0 有缺陷的 {@code validateGraph}（条件边 NPE），
     * 直接构造 {@link CompiledGraph}。条件边的真实路由（EdgeCondition）在运行时由框架处理，不受影响。
     */
    private CompiledGraph buildCompiledGraph(StateGraph graph) throws Exception {
        CompileConfig cc = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build();
        Constructor<CompiledGraph> ctor = CompiledGraph.class
                .getDeclaredConstructor(StateGraph.class, CompileConfig.class);
        ctor.setAccessible(true);
        return ctor.newInstance(graph, cc);
    }
}
