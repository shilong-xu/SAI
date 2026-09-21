package xsl.sai.agent.hook;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.*;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import io.agentscope.harness.agent.tool.SkillManageTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的可观测性中间件：只负责把关键过程打成单行日志，便于肉眼扫读。
 *
 * <ul>
 *   <li><b>内部思考</b> {@code [内部思考]}：思考块完整内容（增量先攒，块结束整段打印，不逐 token 刷屏）</li>
 *   <li><b>SKILL</b>：{@code [SKILL·可用]} 注入了哪些技能（清单不变不重复打印）、
 *       {@code [SKILL·加载]} / {@code [SKILL·管理]} 技能的加载与增删改</li>
 *   <li><b>工具调用</b> {@code [工具调用]}：调用的工具与入参（超长截断）</li>
 * </ul>
 *
 * <p>挂载方式见 {@code AgentAIConfig#harnessAgent(...)} 中的
 * {@code .middlewares(List.of(new AgentTraceMiddleware()))}。
 *
 * <p>官方文档：https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html
 */
public class AgentTraceMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceMiddleware.class);

    /** 工具日志单行上限，避免 write_file 之类的长入参刷满控制台 */
    private static final int ARG_MAX = 300;

    /** 系统提示词里的技能清单块；注意 {@code <usage>} 示例段也含 <skill-id>，必须先定位块再抽取 */
    private static final Pattern AVAILABLE_SKILLS = Pattern.compile("<available_skills>(.*?)</available_skills>", Pattern.DOTALL);
    /** 技能清单中每个 {@code <skill>} 的展示名 */
    private static final Pattern SKILL_NAME = Pattern.compile("<name>([^<]+)</name>");

    // 按「会话@Agent名」记录开始时间（纳秒）与已打印的技能清单：
    // 子 Agent 与主 Agent 共用 sessionId，键必须带上 Agent 名，否则会互相覆盖
    private final Map<String, Long> agentStartNanos = new ConcurrentHashMap<>();
    private final Map<String, String> loggedSkills = new ConcurrentHashMap<>();

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        long start = System.nanoTime();
        String name = agent.getName();
        // sessionId 理论上由 SDK 注入，但为空的场景不能把 NPE 抛进事件流
        String sid = StrUtil.blankToDefault(ctx.getSessionId(), "-");
        agentStartNanos.put(sid + "@" + name, start);
        // 思考增量先攒起来，遇到「思考块结束」整段打印，避免逐 token 刷屏；原事件继续下游透传
        StringBuilder think = new StringBuilder();
        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof ThinkingBlockDeltaEvent d && d.getDelta() != null) {
                        think.append(d.getDelta());
                    } else if (ev instanceof ThinkingBlockEndEvent) {
                        printThinking(name, sid, think);
                    }
                })
                .doOnComplete(() -> log.info("[Agent·结束] {} sessionId --> {}, 耗时={}ms", name, sid, ms(start)))
                .doOnError(e -> log.warn("[Agent·异常] {} sessionId --> {}, error --> {}", name, sid, e.getMessage()))
                // 兜底：结束/异常/取消时若仍有未打印的思考则补打（已打印的会自动跳过）
                .doFinally(sig -> printThinking(name, sid, think));
    }

    /**
     * 打印完整思考内容并清空缓冲；无思考则不打印，避免噪音
     */
    private void printThinking(String agentName, String sid, StringBuilder think) {
        if (think.isEmpty()) {
            return;
        }
        log.info("[内部思考] {} sessionId --> {}", agentName, think.toString().trim());
        think.setLength(0);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (StrUtil.isNotBlank(prompt)) {
            List<String> names = skillNames(prompt);
            String joined = String.join(", ", names);
            // 提示词每轮都会重建，清单没变就不重复打印
            if (!names.isEmpty() && !joined.equals(loggedSkills.put(agent.getName(), joined))) {
                log.info("[SKILL·可用] {} 共 {} 个 --> {}", agent.getName(), names.size(), joined);
            }
        }
        return Mono.just(StrUtil.emptyIfNull(prompt));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 意图分析：模型此刻看到的最近一条用户诉求
        log.info("[意图分析] sessionId --> {}, lastMsg --> {}", ctx.getSessionId(), clip(lastText(input.messages()), 400));
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // 技能加载/管理单独成行，其余工具统一打印「工具名 + 入参」
        for (ToolUseBlock call : input.toolCalls()) {
            String tool = call.getName();
            String label = SkillLoadTool.TOOL_NAME.equals(tool) ? "[SKILL·加载]"
                    : SkillManageTool.NAME.equals(tool) ? "[SKILL·管理]"
                    : "[工具调用]";
            log.info("{} {} --> {}", label, tool, clip(String.valueOf(call.getInput()), ARG_MAX));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        String name = agent.getName();
        String sid = StrUtil.blankToDefault(ctx.getSessionId(), "-");
        // 首次模型调用前的时间 = 框架在「思考」阶段（记忆压缩/MetaTool 规划、上下文拼装、工具 schema 决策）的耗时
        Long began = agentStartNanos.remove(sid + "@" + name);
        if (began != null) {
            log.info("[TTFT·框架内] {} sessionId --> {}, 进入首模型调用前耗时 --> {}ms", name, sid, ms(began));
        }
        return next.apply(input);
    }

    // ---------- helpers ----------

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 从系统提示词的 {@code <available_skills>} 块里抽取技能名；没有技能则返回空列表
     */
    private static List<String> skillNames(String prompt) {
        Matcher block = AVAILABLE_SKILLS.matcher(prompt);
        return block.find()
                ? SKILL_NAME.matcher(block.group(1)).results().map(m -> m.group(1)).toList()
                : List.of();
    }

    /**
     * 取最近一条非空文本消息，用于展示“用户最新诉求 / 当前意图”。
     */
    private static String lastText(List<Msg> msgs) {
        if (msgs == null) {
            return "";
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String t = msgs.get(i).getTextContent();
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        return "";
    }

    /** 编排节点（Planner/Analyzer/AgentScope/Summarizer）也用它做日志截断，保持 public */
    public static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...(trimmed)";
    }
}
