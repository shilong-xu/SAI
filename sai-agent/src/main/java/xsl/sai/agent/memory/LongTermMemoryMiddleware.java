package xsl.sai.agent.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 长期记忆中间件 —— 纯自动模式：召回与沉淀全部由框架驱动，<b>不暴露任何记忆工具、不依赖 Agent 决策</b>。
 *
 * <p><b>为什么从 Hook 改成 Middleware</b>：
 * {@code io.agentscope.core.hook.Hook} / {@code HookEvent} / {@code RuntimeContextAware}
 * 自 2.0.0 起全部被 {@code @Deprecated(forRemoval=true)} 标记（与 {@code LongTermMemoryTools} 同期）。
 * 2.0 的官方扩展位是 {@code Middleware}（onAgent / onReasoning / onActing / onModelCall / onSystemPrompt 五段），
 * 直接把 {@code RuntimeContext} 作为入参传入，不再需要 {@code ThreadLocal} + {@code RuntimeContextAware} 那套。
 *
 * <p><b>两段职责</b>：
 * <ol>
 *   <li><b>召回（onAgent 前置 + onSystemPrompt 注入）</b>：一次 {@code call()} 只检索一次，
 *       结果暂存在 {@link RuntimeContext}；{@code onSystemPrompt} 只做字符串拼接。
 *       必须放在 system 区——它每轮由框架重建，不会堆进 {@code AgentState.context} 污染对话历史，
 *       也不会被 compaction 摘要掉（官方 {@code StaticLongTermMemoryHook} 是把记忆包成 USER 消息追加到输入列表，
 *       多轮后会堆满 {@code <long_term_memory>} 块，这里有意不这么做）。</li>
 *   <li><b>沉淀（onAgent 收尾）</b>：拿到 {@link AgentResultEvent} 里的最终回复，
 *       连同本次输入消息一起交给 {@code MysqlVectorLongTermMemory#record}，fire-and-forget，失败只告警。</li>
 * </ol>
 *
 * <p><b>userId / sessionId</b>：直接取自 {@link RuntimeContext}，不再依赖 Reactor Context 兜底。
 *
 * <p><b>不提供 Agent 主动读写的工具</b>：长期记忆的维护（写什么、什么时候写、召回什么）完全由本中间件决定，
 * 模型只消费注入到 system prompt 的记忆，不参与记忆管理决策——避免模型忘记调用、乱调用、或把噪音写进记忆库。
 *
 * @author SAI
 */
@Slf4j
public class LongTermMemoryMiddleware implements MiddlewareBase {

    /** RuntimeContext 中暂存「本次 call 召回结果」的键 */
    private static final String CTX_RECALL = "sai.ltm.recall";

    /** 框架注入记忆消息使用的 name（用于排除，避免拿记忆块再去检索记忆） */
    private static final String LTM_MSG_NAME = "long_term_memory";

    private final MysqlVectorLongTermMemory longTermMemory;
    private final String defaultUserId;

    public LongTermMemoryMiddleware(MysqlVectorLongTermMemory longTermMemory, String defaultUserId) {
        this.longTermMemory = longTermMemory;
        this.defaultUserId = defaultUserId == null ? "" : defaultUserId;
    }

    /**
     * 一次完整 reply：前置召回 → 放行 → 收尾沉淀。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        final String userId = resolveUserId(ctx);
        final String sessionId = ctx == null ? null : ctx.getSessionId();
        final AtomicReference<Msg> finalMsg = new AtomicReference<>();

        Mono<Void> recall = recall(input == null ? List.of() : input.msgs(), userId, ctx);

        return recall.thenMany(next.apply(input))
                .doOnNext(ev -> {
                    if (ev instanceof AgentResultEvent r && r.getResult() != null) {
                        finalMsg.set(r.getResult());
                    }
                })
                .doFinally(sig -> persist(input == null ? List.of() : input.msgs(),
                        finalMsg.get(), userId, sessionId));
    }

    /**
     * 把召回结果拼到 system prompt 末尾。
     * 只做字符串拼接——检索已在 {@link #onAgent} 前置完成并缓存到 {@link RuntimeContext}，
     * 避免 ReAct 多轮推理时每轮都打一次向量库。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            return Mono.just(currentPrompt);
        }
        String recall = ctx.get(CTX_RECALL, String.class);
        if (recall == null || recall.isBlank()) {
            return Mono.just(currentPrompt);
        }
        return Mono.just(currentPrompt + "\n\n" + recall);
    }

    // ==================== 召回 ====================

    private Mono<Void> recall(List<Msg> inputs, String userId, RuntimeContext ctx) {
        Msg lastUser = findLastUserMessage(inputs);
        if (lastUser == null) {
            return Mono.empty();
        }
        return longTermMemory.retrieve(lastUser, userId)
                .doOnNext(text -> {
                    if (text != null && !text.isBlank() && ctx != null) {
                        ctx.put(CTX_RECALL, text);
                    }
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("[LTM] 长期记忆召回失败（已跳过，不阻断对话）: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** 取最后一条 USER 消息作为检索 query；排除框架注入的记忆消息 */
    private Msg findLastUserMessage(List<Msg> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        for (int i = inputs.size() - 1; i >= 0; i--) {
            Msg m = inputs.get(i);
            if (m == null || m.getRole() != MsgRole.USER) {
                continue;
            }
            if (LTM_MSG_NAME.equals(m.getName())) {
                continue;
            }
            return m;
        }
        return null;
    }

    // ==================== 沉淀 ====================

    /**
     * 对话结束后沉淀：本轮输入消息 + 最终回复。
     * fire-and-forget，不阻塞响应流；失败只告警。
     */
    private void persist(List<Msg> inputs, Msg finalMsg, String userId, String sessionId) {
        List<Msg> msgs = new ArrayList<>(inputs);
        if (finalMsg != null) {
            msgs.add(finalMsg);
        }
        if (msgs.isEmpty()) {
            return;
        }
        longTermMemory.record(msgs, userId, sessionId)
                .subscribe(null, e -> log.warn("[LTM] 记忆沉淀失败（已跳过，不阻断对话）: {}", e.getMessage()));
    }

    // ==================== 辅助 ====================

    private String resolveUserId(RuntimeContext ctx) {
        if (ctx == null) {
            return defaultUserId;
        }
        String uid = ctx.getUserId();
        return (uid == null || uid.isBlank()) ? defaultUserId : uid;
    }
}
