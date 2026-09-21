package xsl.sai.agent;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import xsl.sai.framework.holder.UserHolder;
import xsl.sai.framework.pojo.bo.RequestInfoBO;
import xsl.sai.framework.pojo.vo.LoopStep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentScope的主入口,所有的调用都要走这个类
 * &#064;DATE: 2026/6/15 12:49
 * &#064;AUTHOR: XSL
 *
 */
@Slf4j
@Component
public class AgentAI {

    private final HarnessAgent harnessAgent;
    private final String workspace;
    private final String planDir;

    /** 缓存已注入计划模式权限的会话，避免每轮 run 重复做状态存储查询 */
    private final Set<String> planPermissionCached = ConcurrentHashMap.newKeySet();
    /** 缓存已放开框架工具权限（BYPASS + 无 ASK）的会话，避免每轮对话重复读状态存储 + 重建权限引擎 */
    private final Set<String> unrestrictedCached = ConcurrentHashMap.newKeySet();

    /**
     * 每个会话正在运行的后台 agent 主循环订阅。
     * 工具执行被刻意解耦到这个【独立订阅】里，而非直接绑在 SSE 响应流上：
     * 这样即便客户端中途停止/断连（SSE 订阅被取消），后台 loop 仍会跑完，
     * 工具执行与状态落盘不受影响，不再产生“工具执行被中断”的兜底错误。
     */
    private final Map<String, Disposable> runningLoops = new ConcurrentHashMap<>();

    public AgentAI(HarnessAgent harnessAgent,
                   @Value("${agentScope.workspace:.agentscope/workspace}") String workspace,
                   @Value("${agentScope.evolution.plan.planFileDirectory:plans}") String planDir) {
        this.harnessAgent = harnessAgent;
        this.workspace = workspace;
        this.planDir = planDir;
    }

    /**
     * 主动中断指定会话正在运行的后台 agent 主循环（思考 / 工具执行）。
     *
     * <p><b>为什么需要这个方法：</b>为隔离「客户端断连 / 用户点停止」与「后台工具执行不被中断」，
     * {@link #streamEventsSafe} / {@link #streamLoopSafe} 把 AgentScope 主循环放进独立的
     * {@code Disposable} 后台订阅（登记在 {@link #runningLoops}，键为 {@code userId:sessionId}），
     * 且 SSE 订阅被取消时【不】dispose 该后台 loop。这带来一个副作用——前端「停止」按钮只取消了
     * SSE 订阅（ChatStreamRegistry.cancel），后台 loop 仍在跑，表现为「点了停止思考却继续、下次
     * 对话续上一段残句」。</p>
     *
     * <p><b>本方法</b>按会话精确 dispose 对应的后台 loop 订阅：键 {@code userId:sessionId}
     * 天然按会话隔离（避免无会话参数 interrupt 的并发竞态），dispose 后 AgentScope 内部对应的
     * LLM 流与 ReAct 循环随之停止，实现真正的「停止」。</p>
     *
     * @return 是否真的存在并中断了活跃的后台 loop
     */
    public boolean stopSession(String userId, String sessionId) {
        String convKey = userId + ":" + sessionId;
        Disposable bg = runningLoops.get(convKey);
        if (bg != null && !bg.isDisposed()) {
            bg.dispose();
            // dispose 后由 bg 自身的 onComplete/onError 负责从 runningLoops 移除；
            // 这里不立即 remove，避免与「新消息已 dispose 旧 loop 并 put 新值」的时序竞争。
            log.info("[Agent] 已主动中断会话后台 loop: {}", convKey);
            return true;
        }
        // 兜底：若后台 loop 已结束（runningLoops 已清理），尝试通知框架层面中断，
        // 覆盖「loop 刚结束但 AgentScope 内部仍有残留轮次」的极小概率场景。
        try {
            harnessAgent.interrupt();
        } catch (Throwable t) {
            // interrupt 无会话参数，仅作兜底；失败不影响主流程
            log.debug("[Agent] 兜底 interrupt 失败（忽略）: {}", t.getMessage());
        }
        return false;
    }

    /**
     * 纯文本对话
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   对话内容
     * @return 文本流
     */
    public Flux<String> process(String userId, String sessionId, String message) {
        return process(userId, sessionId, message, null, null);
    }

    /**
     * 带文件的对话（兼容旧调用，文件名未知时由底层回退默认名）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   文字内容
     * @param source    文件源
     * @return 文本流
     */
    public Flux<String> process(String userId, String sessionId, String message, Source source) {
        return process(userId, sessionId, message, source, null);
    }

    /**
     * 对话核心方法：同时支持「纯文本」与「文件上传」两种模式
     *
     * <ul>
     *   <li>source 为 null —— 纯文本对话，直接构造 {@link UserMessage}；</li>
     *   <li>source 非 null —— 文件上传对话，将文本与文件作为多模态内容一并提交，
     *       文件数据块名称优先使用真实文件名 fileName，缺失时回退默认名。</li>
     * </ul>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   文字内容（附言）
     * @param source    文件源（纯文本时为 null）
     * @param fileName  上传文件名（用于让模型识别文件类型/名称；可空）
     * @return 文本流
     */
    public Flux<String> process(String userId, String sessionId, String message, Source source, String fileName) {
        UserMessage userMessage;
        if (Objects.isNull(source)) {
            log.info("[Agent·请求] userId --> {}, sessionId --> {}, msgLen --> {}", userId, sessionId,
                    StrUtil.isNotBlank(message) ? message.length() : 0);
            userMessage = new UserMessage(message);
        } else {
            String blockName = StrUtil.isNotBlank(fileName) ? fileName : "uploaded_file";
            log.info("[Agent·请求] userId --> {}, sessionId --> {}, file --> {}, msgLen --> {}", userId, sessionId, blockName,
                    StrUtil.isNotBlank(message) ? message.length() : 0);
            userMessage = UserMessage.builder()
                    .content(
                            List.of(
                                    TextBlock.builder().text(message).build(),
                                    DataBlock.builder()
                                            .source(source)
                                            .name(blockName)
                                            .build()
                            )
                    )
                    .build();
        }
        // 计划模式下，每次 run 前确保：① 权限模式 BYPASS（plan_write 放行）；
        // ② 注入 plan_exit 的 DENY 规则（阻止模型自行退出计划模式）。
        // 计划模式的「只读约束」由 PlanModeMiddleware 独立实施（仅放行 plan_write 等白名单工具），
        // 与 PermissionMode 无关，因此 BYPASS 不会让模型在计划模式下越权执行。
        // 注意：PlanExitTool.checkPermissions 本身有「调用 manager.exit 关闭计划模式」的副作用，
        // 因此必须在 checkDenyRules 阶段就 DENY 掉 plan_exit，使其 checkPermissions 不会被调用。
        if (harnessAgent.isPlanModeActive(userId, sessionId)) {
            ensurePlanModeDenyExit(userId, sessionId);
        }
        // 非计划模式下，放开框架自带工具的全部权限（移除全部 ASK 规则 + 设为 BYPASS）：
        // agent 可自由调用 SQL / Shell / 联网 / 向量检索 / memory_* 等框架自带工具，无需逐次人工确认
        ensureBuiltinToolsUnrestricted(userId, sessionId);
        // 从 Reactor 上下文（AuthWebFilter 写入的当前用户）取出账号与姓名，注入 RunContext，
        // 供下游 agent 运行时读取（如人格、记忆隔离等）。
        return Flux.deferContextual(view -> {
            RuntimeContext rc = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .build();
            RequestInfoBO bo = UserHolder.from(view);
            if (bo.getUserInfo() != null) {
                if (StrUtil.isNotBlank(bo.getUserInfo().getUsername())) {
                    rc.put("username", bo.getUserInfo().getUsername());
                }
                if (StrUtil.isNotBlank(bo.getUserInfo().getDisplayName())) {
                    rc.put("displayName", bo.getUserInfo().getDisplayName());
                }
            }
            return streamEventsSafe(userId, sessionId, userMessage, rc);
        });
    }

    /**
     * 带 HITL 防御兜底的流式对话。
     *
     * <p><b>关键：工具执行与 SSE 流生命周期解耦。</b>
     * 框架的 agent 主循环（含工具执行、状态落盘）原本直接作为 SSE 响应 Flux 被订阅；
     * 一旦该 Flux 被取消（用户点“停止”、客户端断连、或任何订阅取消），正在执行的工具就被
     * 中断，状态以“有 tool_call 无 tool_result”落盘，下一轮被
     * {@code maybePatchPendingToolCalls} 兜底补成
     * “Previous tool execution failed or was interrupted. Tool: xxx”——这正是所有工具
     * （包括 get_current_time）一致报错的元凶。</p>
     *
     * <p>这里把主循环放进一个【独立订阅】（后台 loop），文本增量通过 {@link Sinks.Many} 桥接出来；
     * SSE 只从 Sink 读取。SSE 订阅被取消时，后台 loop 仍会跑完（工具执行与状态落盘不受影响），
     * 因此工具不再因流中断而失败。新消息进来时会先 dispose 上一条仍在跑的后台 loop，避免同会话并发竞态。</p>
     */
    private Flux<String> streamEventsSafe(String userId, String sessionId, UserMessage userMessage, RuntimeContext rc) {
        String convKey = userId + ":" + sessionId;

        // 同一会话只允许一条后台 loop：新消息到来时先停掉上一条（无论其是否已结束），
        // 防止并发同会话竞态导致状态错乱。
        // 注意：ConcurrentHashMap 不允许 null 值，故先用 get 取旧值再 put 新值，切勿 put(convKey, null)。
        Disposable prev = runningLoops.get(convKey);
        if (prev != null && !prev.isDisposed()) {
            prev.dispose();
        }

        // replay().all()：缓冲已产生的增量并重放给 SSE 订阅者，避免 SSE 订阅晚于首帧时丢前几段文本
        Sinks.Many<String> sink = Sinks.many().replay().all();
        // 用数组持有 bg：lambda 内引用局部变量会被“可能未初始化”判定，数组元素访问可规避
        final Disposable[] bgHolder = new Disposable[1];
        bgHolder[0] = harnessAgent.streamEvents(userMessage, rc)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .subscribe(
                        sink::tryEmitNext,
                        err -> {
                            sink.tryEmitError(err);
                            // bg 自身出错：仅当本 loop 仍是当前活跃项时才移除，避免误删新 loop
                            runningLoops.remove(convKey, bgHolder[0]);
                        },
                        () -> {
                            sink.tryEmitComplete();
                            // bg 自身正常结束：仅当本 loop 仍是当前活跃项时才移除，避免误删新 loop
                            runningLoops.remove(convKey, bgHolder[0]);
                        });
        runningLoops.put(convKey, bgHolder[0]);

        // 重要：SSE 的生命周期【不】管理 bg loop——无论 SSE 被取消、正常完成还是出错，
        // 都不要 dispose bg，让后台 loop 自行跑完（工具执行与状态落盘不被流中断影响）。
        // map 条目的清理完全由 bg 自身终止时负责（见上方 onComplete/onError）。
        return sink.asFlux()
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (isMemoryHitlPause(ex)) {
                        log.warn("[Memory] 会话因 memory_save 待确认被卡死，已重置状态，请用户重发: {}/{}",
                                userId, sessionId);
                        tryDeleteSession(userId, sessionId);
                        return Flux.just("⚠️ 上一轮会话因记忆保存待确认被卡住，我已为你重置，请重新发送这条消息。");
                    }
                    return Flux.error(ex);
                });
    }

    /** 判断异常是否由任一 memory_* 工具的 HITL 待确认暂停引起（兼容 memory_save / memory_get / memory_search 等） */
    private boolean isMemoryHitlPause(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return false;
        }
        boolean paused = msg.contains("paused for human-in-the-loop confirmation");
        if (!paused) {
            return false;
        }
        // 命中任一 memory_* 工具名即视为记忆类 HITL 卡死
        for (String prefix : new String[] {"memory_save", "memory_get", "memory_search", "memory_"}) {
            if (msg.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 清空卡死的会话状态，使其可重新开始（失败时仅告警，不影响主流程） */
    private void tryDeleteSession(String userId, String sessionId) {
        try {
            if (harnessAgent.getStateStore().exists(userId, sessionId)) {
                harnessAgent.getStateStore().delete(userId, sessionId);
            }
        } catch (Throwable t) {
            log.warn("[Memory] 清空卡死会话 {}/{} 失败（忽略）: {}", userId, sessionId, t.getMessage());
        }
    }

    /**
     * 自驱动 Agent Loop：把 AgentScope Harness 的 ReAct 主循环（推理 → 调工具 → 观察 → 再推理）
     * 完整事件流映射为 {@link LoopStep}，供前端以 SSE 实时渲染每一步。
     *
     * <p>与 {@link #process(String, String, String, Source, String)} 的差异仅在于输出粒度：
     * process 只暴露文本增量（TextBlockDeltaEvent），而本方法订阅 {@code streamEvents} 的
     * 全部事件（THINKING_BLOCK / TOOL_CALL / TOOL_RESULT / TEXT_BLOCK …），把 loop 的
     * 推理、动作、观察逐条映射成 LoopStep。底层智能体、工具箱、记忆、计划模式完全一致。</p>
     *
     * <p>权限 / RuntimeContext 装配沿用 {@link #process(String, String, String, Source, String)}，
     * 保证工具免确认、记忆与计划模式在多轮间持续生效。</p>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID（同时是 AgentScope sessionId）
     * @param message   用户原始请求
     * @return 每一步的 {@link LoopStep} 流（THINK / ACTION / OBSERVATION / ANSWER / DONE …）
     */
    public Flux<LoopStep> processLoop(String userId, String sessionId, String message) {
        UserMessage userMessage = new UserMessage(message);
        // 计划模式：确保 BYPASS + 禁止 plan_exit（与 process 一致）
        if (harnessAgent.isPlanModeActive(userId, sessionId)) {
            ensurePlanModeDenyExit(userId, sessionId);
        }
        // 非计划模式：放开全部框架自带工具权限，避免工具 HITL 暂停
        ensureBuiltinToolsUnrestricted(userId, sessionId);

        return Flux.deferContextual(view -> {
            RuntimeContext rc = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .build();
            RequestInfoBO bo = UserHolder.from(view);
            if (bo.getUserInfo() != null) {
                if (StrUtil.isNotBlank(bo.getUserInfo().getUsername())) {
                    rc.put("username", bo.getUserInfo().getUsername());
                }
                if (StrUtil.isNotBlank(bo.getUserInfo().getDisplayName())) {
                    rc.put("displayName", bo.getUserInfo().getDisplayName());
                }
            }
            return streamLoopSafe(userId, sessionId, userMessage, rc);
        });
    }

    /**
     * 带 HITL 防御兜底的流式 Agent Loop。
     *
     * <p>沿用 {@link #streamEventsSafe} 的设计：把主循环放进独立订阅（后台 loop），
     * 步骤通过 {@link Sinks.Many} 桥接出来，SSE 只从 Sink 读取。这样即便客户端中途断连，
     * 后台 loop 仍会跑完（工具执行与记忆落盘不受影响），不会因流中断导致工具失败。</p>
     */
    private Flux<LoopStep> streamLoopSafe(String userId, String sessionId,
                                          UserMessage userMessage, RuntimeContext rc) {
        String convKey = userId + ":" + sessionId;
        // 同一会话只允许一条后台 loop：新消息到来时先停掉上一条
        Disposable prev = runningLoops.get(convKey);
        if (prev != null && !prev.isDisposed()) {
            prev.dispose();
        }

        Sinks.Many<LoopStep> sink = Sinks.many().replay().all();
        final Disposable[] bgHolder = new Disposable[1];
        // 迭代编号（每个 THINKING_BLOCK_START 自增），以及当前工具调用的参数累积缓冲
        AtomicInteger stepRef = new AtomicInteger(0);
        StringBuilder curArgs = new StringBuilder();

        bgHolder[0] = harnessAgent.streamEvents(userMessage, rc)
                .doOnNext(ev -> emitLoopStep(sink, ev, stepRef, curArgs))
                .subscribe(
                        ev -> { /* onNext 已由 doOnNext 处理 */ },
                        err -> {
                            if (isMemoryHitlPause(err)) {
                                sink.tryEmitNext(LoopStep.error(
                                        "上一轮会话因记忆保存待确认被卡住，我已重置，请重新发送这条消息。"));
                                tryDeleteSession(userId, sessionId);
                            } else {
                                sink.tryEmitNext(LoopStep.error("智能体循环异常：" + err.getMessage()));
                            }
                            // 关键：先发 DONE 步骤，再关闭 Sink，使 SSE 流正常终止；
                            // 否则 Sink 不关闭 → Flux 永不 complete → 服务层 .concatWithValues(END)
                            // 与 doOnComplete(落库助手消息) 永远不触发，前端卡在“思考中/停止”。
                            sink.tryEmitNext(LoopStep.done());
                            sink.tryEmitComplete();
                            runningLoops.remove(convKey, bgHolder[0]);
                        },
                        () -> {
                            // 同 onError：必须关闭 Sink，否则 SSE 流不终止（见上方说明）
                            sink.tryEmitNext(LoopStep.done());
                            sink.tryEmitComplete();
                            runningLoops.remove(convKey, bgHolder[0]);
                        });
        runningLoops.put(convKey, bgHolder[0]);

        return sink.asFlux()
                .onErrorResume(IllegalStateException.class, ex ->
                        Flux.just(LoopStep.error(ex.getMessage()), LoopStep.done()));
    }

    /**
     * 把单条 AgentScope 事件映射为一个或多个 {@link LoopStep}，并推入 sink。
     * 仅关注 Agent Loop 关心的事件；其余（MODEL_CALL / DATA_BLOCK / SUBAGENT 等）忽略。
     */
    private void emitLoopStep(Sinks.Many<LoopStep> sink, AgentEvent ev,
                              AtomicInteger stepRef, StringBuilder curArgs) {
        AgentEventType t = ev.getType();
        switch (t) {
            case THINKING_BLOCK_START:
                // 新一轮推理开始：自增迭代编号，后续 THINK/ACTION/OBSERVATION 归到该步
                stepRef.incrementAndGet();
                break;
            case THINKING_BLOCK_DELTA:
                if (ev instanceof ThinkingBlockDeltaEvent d) {
                    sink.tryEmitNext(LoopStep.thinking(stepRef.get(), d.getDelta()));
                }
                break;
            case TOOL_CALL_START:
                // 动作开始：先发工具名（参数稍后随 TOOL_CALL_END 补全）
                curArgs.setLength(0);
                if (ev instanceof ToolCallStartEvent s) {
                    sink.tryEmitNext(LoopStep.action(stepRef.get(), s.getToolCallName(), ""));
                }
                break;
            case TOOL_CALL_DELTA:
                // 工具参数 JSON 增量累积
                if (ev instanceof ToolCallDeltaEvent d) {
                    curArgs.append(d.getDelta());
                }
                break;
            case TOOL_CALL_END:
                // 动作结束：补全参数全文
                sink.tryEmitNext(LoopStep.action(stepRef.get(), null, curArgs.toString()));
                break;
            case TOOL_RESULT_TEXT_DELTA:
                if (ev instanceof ToolResultTextDeltaEvent d) {
                    sink.tryEmitNext(LoopStep.observation(stepRef.get(), d.getDelta()));
                }
                break;
            case TOOL_RESULT_END:
                // 观察终态：SUCCESS / ERROR / INTERRUPTED / DENIED
                if (ev instanceof ToolResultEndEvent e) {
                    ToolResultState st = e.getState();
                    sink.tryEmitNext(LoopStep.observationEnd(
                            stepRef.get(), st == null ? "unknown" : st.name().toLowerCase()));
                }
                break;
            case TEXT_BLOCK_DELTA:
                // 最终回答文本增量（step=0，前端拼入消息气泡）
                if (ev instanceof TextBlockDeltaEvent d) {
                    sink.tryEmitNext(LoopStep.answer(d.getDelta()));
                }
                break;
            case HINT_BLOCK:
                if (ev instanceof HintBlockEvent h) {
                    sink.tryEmitNext(LoopStep.hint(h.getHint()));
                }
                break;
            case EXCEED_MAX_ITERS:
                sink.tryEmitNext(LoopStep.maxIters());
                break;
            case REQUIRE_USER_CONFIRM:
            case REQUIRE_EXTERNAL_EXECUTION:
                // 自动循环中不应出现人工确认；若出现则提示并结束，避免 loop 挂起
                sink.tryEmitNext(LoopStep.error("智能体请求人工确认/外部执行，自动循环无法继续。"));
                break;
            default:
                // AGENT_END 等由 onComplete 统一发 DONE；其余事件忽略
                break;
        }
    }

    /* ============================================================
     * 计划模式（Plan Mode）
     * ------------------------------------------------------------
     * 参考 AgentScope Java 官方文档（harness/plan-mode）：
     *   开启后进入「只读阶段」——仅允许只读工具 + plan_enter/plan_write/
     *   plan_exit/todo_write 四个白名单工具，其余工具调用被框架拒绝。
     *   这里采用文档里的「管理台按钮」式程序化控制：由前端开关调用
     *   enterPlanMode/exitPlanMode，避免模型自行 plan_exit 绕过人工审批
     *   （plan_exit 的 HITL 在无 handler 时会失败）。
     * ============================================================ */

    /** 计划模式状态视图：active 是否处于计划模式；phase 阶段；plan 计划内容；planFile 计划文件路径 */
    public record PlanStatusView(boolean active, String phase, String plan, String planFile) {}
    /** 计划文件定位结果 */
    public record PlanInfo(String path, String content) {}

    /**
     * 进入计划模式：切换到只读阶段，并把权限上下文设为「BYPASS + 禁止 plan_exit」。
     * - BYPASS 放行 plan_write（写计划文件），不再有 "Permission denied by user"。
     * - 但 BYPASS 同时会放行 plan_exit，导致模型可自行退出计划模式、绕过人工审批；
     *   因此额外追加一条 plan_exit 的 DENY 规则（checkDenyRules 在 BYPASS 之前短路生效），
     *   模型无法自行 plan_exit，只能由前端按钮走程序化 exitPlanMode 退出。
     * - Plan Mode 的「只读约束」由 PlanModeMiddleware 独立实施（仅放行 plan_write 等白名单），
     *   与权限模式无关，故 BYPASS 不会让模型在计划模式下越权执行。
     * 空白会话也能直接开启：enterPlanMode 会惰性创建会话状态，
     * 用户随后发送的首条消息即在该计划模式下处理。
     */
    public void enterPlanMode(String userId, String sessionId) {
        // 进入计划模式：权限上下文将被重写为「BYPASS + plan_exit DENY」，与「放开全部自带工具」状态
        // 不再等价，故清掉该会话的放开缓存，使后续 ensureBuiltinToolsUnrestricted 重新计算（并再次 add）。
        unrestrictedCached.remove(userId + ":" + sessionId);
        // 进入计划模式前先清除上一轮残留的 PLAN.md：
        // 无论是「批准执行后文件保留」还是「取消清理不彻底/未重启」，
        // 再次进入计划模式都应从干净状态开始，避免 findPlanInfo 回显旧计划
        // （表现为「执行或取消后计划仍未清除」）。仅清理文件，不影响当前会话与历史消息。
        deletePlanFile();
        harnessAgent.enterPlanMode(userId, sessionId);
        // 注入「BYPASS + plan_exit DENY」权限上下文，阻止模型自行退出计划模式。
        ensurePlanModeDenyExit(userId, sessionId);
    }

    /**
     * 确保某会话的计划模式权限上下文为「BYPASS + plan_exit DENY」：
     * - BYPASS 放行 plan_write（写计划文件），不再有 "Permission denied by user"；
     * - plan_exit 的 DENY 在 checkDenyRules 阶段短路生效，早于 BYPASS 放行判断，
     *   使 PlanExitTool.checkPermissions（其副作用会调用 manager.exit 关闭计划模式）根本不会被调用，
     *   模型无法自行退出，只能由前端按钮走程序化 exitPlanMode 退出。
     * 幂等：若已含 plan_exit 的 DENY 规则则不再重复注入；且在每轮 run 前也会调用，
     * 即使 enterPlanMode 当时状态尚未建好（getAgentState 为 null）也能在 run 时补上。
     */
    private void ensurePlanModeDenyExit(String userId, String sessionId) {
        String key = userId + ":" + sessionId;
        if (planPermissionCached.contains(key)) {
            harnessAgent.setPermissionMode(userId, sessionId, PermissionMode.BYPASS);
            return;
        }
        try {
            ReActAgent delegate = harnessAgent.getDelegate();
            AgentState st = delegate.getAgentState(userId, sessionId);
            if (st == null) {
                return;
            }
            PermissionContextState ctx = st.getPermissionContext();
            if (ctx == null) {
                ctx = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
            }
            Map<String, List<PermissionRule>> deny = ctx.getDenyRules();
            boolean hasDeny = deny != null
                    && deny.getOrDefault("plan_exit", Collections.emptyList())
                    .stream().anyMatch(r -> r.behavior() == PermissionBehavior.DENY);
            if (!hasDeny) {
                st.setPermissionContext(buildPlanModePermissionContext(ctx));
                log.info("[PlanMode] 已为 {}/{} 注入 plan_exit DENY", userId, sessionId);
            }
            harnessAgent.setPermissionMode(userId, sessionId, PermissionMode.BYPASS);
            planPermissionCached.add(key);
        } catch (Throwable t) {
            log.error("[PlanMode] 注入 plan_exit DENY 失败: {}", t.getMessage(), t);
        }
    }

    /**
     * 基于旧权限上下文构建计划模式专用上下文，模式设为 BYPASS，并写入一条 plan_exit 的 DENY 规则。
     * - 保留 allow / deny 规则及工作目录（避免丢失框架默认配置）。
     * - 刻意不复制 ASK 规则：ASK 规则在 checkAskRules 阶段优先于 BYPASS 短路返回，
     *   会导致 get_current_time 等只读工具进入 HITL 暂停，与计划模式「免打扰」的预期相悖。
     * - plan_exit 的 DENY 在 checkDenyRules 阶段短路，早于 BYPASS 放行判断，
     *   阻止模型自行退出计划模式。
     */
    private PermissionContextState buildPlanModePermissionContext(PermissionContextState old) {
        PermissionContextState.Builder b = PermissionContextState.builder().mode(PermissionMode.BYPASS);
        old.getAllowRules().forEach((tool, rules) -> rules.forEach(r -> b.addAllowRule(tool, r)));
        // 不复制 ASK 规则，避免计划模式下触发 HITL 暂停
        old.getDenyRules().forEach((tool, rules) -> rules.forEach(r -> b.addDenyRule(tool, r)));
        old.getWorkingDirectories().forEach(b::addWorkingDirectory);
        // 阻止模型自行 plan_exit：只能由前端按钮走程序化 exitPlanMode 退出
        b.addDenyRule("plan_exit", new PermissionRule("plan_exit", null, PermissionBehavior.DENY, "app"));
        return b.build();
    }

    /**
     * 非计划模式下，放开<b>全部框架自带工具</b>的权限，使其无需任何人工确认即可被 agent 调用。
     *
     * 依据 AgentScope 官方权限文档（permission-system）：
     *   - {@link PermissionMode#BYPASS} 会跳过工具的 safety ASK（如危险路径保护），未命中的调用一律放行；
     *   - 但「显式 ASK 规则在每个 mode 下都始终生效（包括 BYPASS）」。框架默认给 memory_* 等写入类工具
     *     挂的 ASK 规则仍会优先短路、触发 HITL 暂停卡死（即之前 memory_save 报错的元凶）。
     * 因此本方法做两件事：
     *   ① 把模式设为 BYPASS，跳过工具自身的动态安全检查，放开 SQL / Shell / 联网 / 向量检索 /
     *      memory_* 等全部框架自带工具；
     *   ② 移除所有显式 ASK 规则（无论哪个工具），避免它们优先短路导致暂停。
     * 显式 DENY 规则与 ALLOW 规则原样保留：DENY 作为最后护栏（BYPASS 下 deny 始终生效），
     * ALLOW 无害。当且仅当确实存在 ASK 规则、或模式非 BYPASS 时才重建上下文（幂等，避免每轮无谓重建）。
     *
     * 注：个人工具为本地可信运行，放开框架自带工具符合预期。若需对个别危险操作保留护栏，
     * 可在此追加显式 deny 规则（如 "Bash" 的 "rm:*"），BYPASS 下 DENY 始终优先于放行。
     */
    private void ensureBuiltinToolsUnrestricted(String userId, String sessionId) {
        String key = userId + ":" + sessionId;
        // 幂等缓存：同一会话只要放开过一次（已是 BYPASS 且无 ASK 规则），后续每轮对话直接跳过，
        // 避免每轮都走 getAgentState（状态存储同步 IO）+ setPermissionMode（重建权限引擎并落盘），
        // 这是普通对话（如「记单词」）每轮额外延迟的主要来源之一。
        if (unrestrictedCached.contains(key)) {
            return;
        }
        try {
            ReActAgent delegate = harnessAgent.getDelegate();
            AgentState st = delegate.getAgentState(userId, sessionId);
            if (st == null) {
                return;
            }
            PermissionContextState ctx = st.getPermissionContext();
            if (ctx == null) {
                ctx = PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
            }
            Map<String, List<PermissionRule>> ask = ctx.getAskRules();
            boolean hasAsk = ask != null && ask.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(r -> r.behavior() == PermissionBehavior.ASK);
            if (!hasAsk && ctx.getMode() == PermissionMode.BYPASS) {
                return;
            }
            PermissionContextState.Builder b = PermissionContextState.builder().mode(PermissionMode.BYPASS);
            // 复制 ALLOW / DENY 规则与工作目录，原样保留（DENY 在 BYPASS 下仍作护栏）
            if (ctx.getAllowRules() != null) {
                ctx.getAllowRules().forEach((t, rs) -> rs.forEach(r -> b.addAllowRule(t, r)));
            }
            if (ctx.getDenyRules() != null) {
                ctx.getDenyRules().forEach((t, rs) -> rs.forEach(r -> b.addDenyRule(t, r)));
            }
            ctx.getWorkingDirectories().forEach(b::addWorkingDirectory);
            // 不复制任何 ASK 规则：放开全部框架自带工具，不再触发 HITL 暂停
            st.setPermissionContext(b.build());
            // 关键：重建权限引擎缓存。仅 setPermissionContext 不会刷新内存中的 PermissionEngine
            // 实例，运行时仍按旧的（DEFAULT）模式与旧 ASK 规则决策；setPermissionMode 内部基于
            // 当前 context 重建引擎并落盘，使 BYPASS 立即生效（与计划模式做法一致）。
            harnessAgent.setPermissionMode(userId, sessionId, PermissionMode.BYPASS);
            // 标记本会话已放开，后续每轮对话命中缓存直接跳过（见方法开头），避免重复 IO 与引擎重建。
            unrestrictedCached.add(key);
            log.info("[Permission] 已放开 {}/{} 的框架自带工具权限（BYPASS + 移除全部 ASK 规则）",
                    userId, sessionId);
        } catch (Throwable t) {
            log.error("[Permission] 放开框架自带工具权限失败: {}", t.getMessage(), t);
        }
    }

    /**
     * 退出计划模式。
     *
     * @param approve true  = 用户在 UI 侧「批准执行」：保留计划文件并登记到 AgentState 的
     *                       currentPlanFile，使框架在下一轮注入「已批准计划存在，请逐步执行」提示；
     *                false = 用户「取消」：清除 currentPlanFile，计划不被采用（等效拒绝）。
     */
    public void exitPlanMode(String userId, String sessionId, boolean approve) {
        planPermissionCached.remove(userId + ":" + sessionId);
        // 退出计划模式后权限恢复 DEFAULT，与「放开全部自带工具」缓存不等价，清理以避免状态错乱；
        // 下一轮 ensureBuiltinToolsUnrestricted 会重新放开并再次加入缓存。
        unrestrictedCached.remove(userId + ":" + sessionId);
        if (!harnessAgent.getStateStore().exists(userId, sessionId)) {
            return;
        }
        try {
            harnessAgent.setPermissionMode(userId, sessionId, PermissionMode.DEFAULT);
        } catch (Throwable t) {
            log.warn("[PlanMode] 恢复默认权限失败（忽略）: {}", t.getMessage());
        }
        harnessAgent.exitPlanMode(userId, sessionId);
        // 关键：框架的 exitPlanMode 仅把 planActive 置否，并不会登记 approved 计划。
        // 框架只在 currentPlanFile 非空时才在下一轮注入「BUILD 模式 / 请逐步执行计划」提示，
        // 而模型若仅以文本回复计划（未调用 plan_write），该字段为空，导致批准后 AI 不知要执行计划，
        // 表现为「点了批准却像被拒绝」。此处显式登记/清除，让批准真正生效。
        applyPlanApprovalState(userId, sessionId, approve);
        // 取消（approve=false）时彻底删除磁盘上的计划文件：否则 PLAN.md 残留，
        // 用户再次进入计划模式时 findPlanInfo 会回显旧计划，表现为「取消后计划不消失」。
        // 批准（approve=true）不删文件，需保留供 AI 读取计划逐步执行。
        if (!approve) {
            deletePlanFile();
        }
    }

    /**
     * 登记/清除计划批准状态。approved 时把 PLAN.md 路径写入 AgentState.currentPlanFile，
     * 使框架 BUILD 模式提示生效；取消时清空，等效拒绝计划。
     *
     * 注意：必须走 {@code harnessAgent.getDelegate()}（即内部 ReActAgent）的
     * getAgentState/saveAgentState —— 这两条会同步更新 ReActAgent 的内存 stateCache，
     * 而 {@code getStateStore().get/save} 会绕过该缓存，导致下一轮 run 读到的仍是旧的
     * currentPlanFile=null，BUILD 提示不触发，计划看似「被拒绝」。
     */
    private void applyPlanApprovalState(String userId, String sessionId, boolean approve) {
        try {
            ReActAgent delegate = harnessAgent.getDelegate();
            AgentState state = delegate.getAgentState(userId, sessionId);
            if (state == null) {
                return;
            }
            PlanModeContextState pm = state.getPlanModeContext();
            if (approve) {
                PlanInfo info = findPlanInfo();
                if (info != null) {
                    pm.setCurrentPlanFile(info.path());
                }
            } else {
                pm.setCurrentPlanFile(null);
            }
            delegate.saveAgentState(userId, sessionId);
        } catch (Throwable t) {
            log.warn("[PlanMode] 登记计划批准状态失败（忽略）: {}", t.getMessage());
        }
    }

    /**
     * 当前会话是否处于计划模式。
     */
    public boolean isPlanModeActive(String userId, String sessionId) {
        return harnessAgent.isPlanModeActive(userId, sessionId);
    }

    /**
     * 计划模式阶段检测（弥补框架未暴露计划终态枚举的缺口）：
     * <ul>
     *   <li>NO_SESSION        —— 会话不存在（应先发一条消息）</li>
     *   <li>EXITED            —— 计划模式未激活（已退出或从未进入）</li>
     *   <li>PLANNING          —— 处于计划模式但 AI 尚未写出计划文件（规划中/规划失败）</li>
     *   <li>AWAITING_APPROVAL —— 计划已写好，等待用户在 UI 侧批准执行</li>
     * </ul>
     */
    public PlanStatusView getPlanStatus(String userId, String sessionId) {
        boolean exists = harnessAgent.getStateStore().exists(userId, sessionId);
        boolean active = exists && harnessAgent.isPlanModeActive(userId, sessionId);
        String plan = "";
        String planFile = "";
        if (active) {
            PlanInfo info = findPlanInfo();
            if (info != null) {
                plan = info.content();
                planFile = info.path();
            }
        }
        String phase = !exists ? "NO_SESSION"
                : !active ? "EXITED"
                : (!plan.isBlank() ? "AWAITING_APPROVAL" : "PLANNING");
        return new PlanStatusView(active, phase, plan, planFile);
    }

    /**
     * 读取计划文件内容（plans/PLAN.md，兼容会话隔离子目录）。
     * 计划模式未写文件时返回 null，前端可回退展示对话内容。
     */
    public String getPlanContent(String userId, String sessionId) {
        PlanInfo info = findPlanInfo();
        return info == null ? null : info.content();
    }

    /**
     * 在 workspace/planDir 下定位 PLAN.md（优先根目录，其次递归嵌套会话子目录），返回路径与内容。
     */
    private PlanInfo findPlanInfo() {
        Path base = Paths.get(workspace, planDir);
        try {
            Path direct = base.resolve("PLAN.md");
            if (Files.exists(direct)) {
                return new PlanInfo(direct.toString(), Files.readString(direct, StandardCharsets.UTF_8));
            }
            // 会话隔离可能在 planDir 下嵌套子目录，递归查找首个 PLAN.md
            if (Files.isDirectory(base)) {
                try (var stream = Files.walk(base, 3)) {
                    return stream
                            .filter(p -> Files.isRegularFile(p) && "PLAN.md".equals(p.getFileName().toString()))
                            .findFirst()
                            .map(p -> {
                                try {
                                    return new PlanInfo(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException e) {
                                    return null;
                                }
                            })
                            .orElse(null);
                }
            }
        } catch (IOException e) {
            log.warn("[PlanMode] 读取计划文件失败: {}", base, e);
        }
        return null;
    }

    /**
     * 兜底：当模型在计划模式下未调用 plan_write（仅以文本回复呈现计划）时，
     * 由前端把本轮回复 POST 回来，这里写入 workspace/planDir/PLAN.md，
     * 使计划面板与「批准执行」流程可用。已存在 PLAN.md 时由调用方判断是否覆盖。
     */
    public void writePlan(String userId, String sessionId, String text) {
        if (StrUtil.isBlank(text)) return;
        Path base = Paths.get(workspace, planDir);
        try {
            Files.createDirectories(base);
            Files.writeString(base.resolve("PLAN.md"), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[PlanMode] 兜底写入计划文件失败: {}", base, e);
        }
    }

    /**
     * 彻底删除计划文件（plans/PLAN.md，兼容会话隔离子目录）。
     * 用于「取消计划」：清除磁盘残留，避免用户再次进入计划模式时旧计划被回显，
     * 表现为「取消后计划不消失」。删除失败仅告警，不影响主流程。
     */
    private void deletePlanFile() {
        Path base = Paths.get(workspace, planDir);
        try {
            if (Files.isDirectory(base)) {
                try (var stream = Files.walk(base, 3)) {
                    stream
                            .filter(p -> Files.isRegularFile(p)
                                    && "PLAN.md".equals(p.getFileName().toString()))
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                    log.info("[PlanMode] 已删除计划文件: {}", p);
                                } catch (IOException ignored) {
                                    // 单文件删除失败不影响其余文件
                                }
                            });
                }
            }
            // 直接路径兜底（避免 walk 因目录不存在而跳过）
            Path direct = base.resolve("PLAN.md");
            if (Files.exists(direct)) {
                Files.delete(direct);
            }
        } catch (IOException e) {
            log.warn("[PlanMode] 删除计划文件失败（忽略）: {}", e.getMessage());
        }
    }

}
