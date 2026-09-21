package xsl.sai.client.service.chat;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.message.Source;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import xsl.sai.client.manager.ChatStreamRegistry;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;
import xsl.sai.framework.holder.UserHolder;
import xsl.sai.framework.result.Result;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话模式策略模板：以模板方法固化「统一对话流水线」的公共步骤，
 * 子类只需实现 {@link #buildStream(ChatContext, ReplyBuffer)} 产出流式结果与最终答案，
 * 以及少量钩子（调度器 / 落库兜底 / 模式名）。
 *
 * <p>公共步骤：
 * ① 从 Reactor 上下文解析 userId；
 * ② 流前持久化用户消息 + 自动生成首条标题；
 * ③ 订阅时登记到 ChatStreamRegistry，支持「停止」中断；
 * ④ 流终止（完成 / 中断 / 异常）后持久化助手消息——
 *    关键修复：原先仅在 {@code doOnComplete} 落库，用户点「停止」触发 CANCEL 时不落库，
 *    导致对话数据丢失。改为 {@code doFinally} 覆盖全部终止信号，确保三种模式都落库；
 * ⑤ 末尾追加 END 标记。
 */
@Slf4j
public abstract class AbstractChatStrategy implements ChatModeStrategy {

    /**
     * SSE 心跳周期：单个编排节点（或长推理）期间流无字节输出，中间代理/浏览器会因空闲超时而断开连接，
     * 导致「思考时间过长→前端断了→后端也不动了」。每 15s 推一条心跳包保活，前端解析时忽略。
     */
    private static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(15);

    protected final MessageService messageService;
    protected final ConversationService conversationService;
    protected final ChatStreamRegistry chatStreamRegistry;

    protected AbstractChatStrategy(MessageService messageService,
                                   ConversationService conversationService,
                                   ChatStreamRegistry chatStreamRegistry) {
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.chatStreamRegistry = chatStreamRegistry;
    }

    @Override
    public Flux<Result> execute(ChatDTO dto, Source source, String fileName) {
        return Flux.deferContextual(view -> {
            ChatContext ctx = ChatContext.from(dto, resolveUserId(view)).withSource(source, fileName);
            ReplyBuffer reply = new ReplyBuffer();
            AtomicReference<Subscription> subRef = new AtomicReference<>();

            // 1) 流前：持久化用户消息 + 首条消息自动生成标题（失败仅记录日志，不影响对话）
            Mono<Void> prepare = ctx.hasConversation()
                    ? messageService.saveUserMessage(ctx.conversationId(), ctx.userId(), ctx.message())
                        .then(conversationService.autoTitleIfFirst(ctx.conversationId(), ctx.message()))
                        .onErrorResume(e -> {
                            log.error("[{}] 持久化用户消息失败, conversationId --> {}", modeName(), ctx.conversationId(), e);
                            return Mono.empty();
                        })
                    : Mono.empty();

            // 2) 流式输出（子类按模式实现），注册到 registry 以支持中断
            Flux<Result> raw = buildStream(ctx, reply)
                    .subscribeOn(streamScheduler())
                    .doOnSubscribe(sub -> {
                        subRef.set(sub);
                        if (ctx.hasConversation()) {
                            chatStreamRegistry.register(ctx.conversationId(), sub);
                        }
                    })
                    // 3) 流终止（完成 / 中断 / 异常）后持久化助手消息：覆盖全部终止信号，确保落库
                    .doFinally(sig -> {
                        if (ctx.hasConversation()) {
                            chatStreamRegistry.unregister(ctx.conversationId(), subRef.get());
                        }
                        persistAssistant(ctx, reply);
                    });

            // 共享以便与心跳合并（心跳需在流结束时自动停止，避免后台空转）
            Flux<Result> stream = raw.publish().autoConnect(1);

            // 心跳保活：SSE 在整个流式响应期间，若某段（长推理 / 工具调用）超过网关空闲超时仍无字节输出，
            // 中间代理/浏览器会判定连接空闲并断开，表现为「思考过久 → 前端突然断了 → 后端也不动了」，
            // 即用户感知的「输出一半就停了」。每 15s 推一条心跳包（前端解析时忽略）保活。
            //
            // 关键修复：旧实现用 takeUntilOther(stream)，其语义是「stream 发出【任意】信号（含第一个 onNext）
            // 即停止心跳」。而首 token 通常在 15s 内到达，导致心跳在第一个字到达后【永久失效】，
            // 后续长工具调用（如记忆 flush、save_knowledge）期间无心跳 → 网关 90s 超时断连 → 输出中断。
            // 改为 takeUntilOther(stream.then(Mono.empty()))：other 仅在 stream【真正终止】（onComplete/onError）
            // 后发出的完成信号才停止心跳，从而在整个响应期间（含流中间的长时间静默）持续保活。
            Flux<Result> heartbeat = Flux.interval(HEARTBEAT_PERIOD)
                    .onBackpressureDrop()
                    .map(t -> Result.success(Map.of("heartbeat", true)))
                    .takeUntilOther(stream.then(Mono.empty()));

            return prepare.thenMany(Flux.merge(stream, heartbeat))
                    .concatWithValues(Result.builder().code(200).message("END").build());
        });
    }

    /** 子类实现：构建该模式的流式输出，并把最终答案文本写入 reply */
    protected abstract Flux<Result> buildStream(ChatContext ctx, ReplyBuffer reply);

    /** 流式执行调度器；阻塞式模式（如 SAA 多轮 LLM）应覆写为 boundedElastic */
    protected Scheduler streamScheduler() {
        return Schedulers.immediate();
    }

    /** 落库文本解析：默认取 answer；子类可覆写以提供中断兜底（如 SAA 未跑到总结节点时回退到各步文本） */
    protected String resolveReply(ReplyBuffer reply) {
        return reply.answer();
    }

    /** 模式名（仅用于日志） */
    protected abstract String modeName();

    private void persistAssistant(ChatContext ctx, ReplyBuffer reply) {
        if (!ctx.hasConversation()) {
            return;
        }
        String text = resolveReply(reply);
        if (StrUtil.isNotBlank(text)) {
            // token 近似值：用回复文本长度作为消耗量近似（流式模式下 LLM 不暴露精确 usage）
            int tokens = text.length();
            messageService.saveAssistantMessage(ctx.conversationId(), ctx.userId(), text, tokens)
                    .subscribeOn(Schedulers.boundedElastic())
                    .retry(2)
                    .doOnSuccess(v -> log.info("[{}] 助手消息已落库, conversationId --> {}, len --> {}, tokens --> {}",
                            modeName(), ctx.conversationId(), text.length(), tokens))
                    .doOnError(e -> log.error("[{}] 保存助手消息失败, conversationId --> {}", modeName(), ctx.conversationId(), e))
                    .subscribe();
        }
    }

    private String resolveUserId(reactor.util.context.ContextView view) {
        String uid = UserHolder.getUserId(view);
        return StrUtil.isNotBlank(uid) ? uid : "default";
    }
}
