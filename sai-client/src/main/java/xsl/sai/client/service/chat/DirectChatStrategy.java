package xsl.sai.client.service.chat;

import io.agentscope.core.message.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;
import xsl.sai.client.manager.ChatStreamRegistry;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.pojo.vo.ChatVO;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;
import xsl.sai.framework.result.Result;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 普通对话策略：直连 AgentScope 核心智能体做逐字流式输出。
 * 支持纯文本与文件上传（多模态）两种信封，文件源由 Controller 解析后透传。
 */
@Slf4j
@Service
@Order(3)
public class DirectChatStrategy extends AbstractChatStrategy {

    private final AgentAI agentAI;

    public DirectChatStrategy(AgentAI agentAI,
                              MessageService messageService,
                              ConversationService conversationService,
                              ChatStreamRegistry chatStreamRegistry) {
        super(messageService, conversationService, chatStreamRegistry);
        this.agentAI = agentAI;
    }

    @Override
    public boolean supports(ChatDTO dto) {
        // 普通对话是兜底模式：SAA 与思考追踪均未开启时命中
        return !dto.isSaaMode() && !dto.isLoopMode();
    }

    @Override
    protected String modeName() {
        return "Chat";
    }

    @Override
    protected Flux<Result> buildStream(ChatContext ctx, ReplyBuffer reply) {
        long startAt = System.currentTimeMillis();
        AtomicLong firstAt = new AtomicLong(0L);
        AtomicLong deltaCount = new AtomicLong(0L);

        Flux<String> agentStream = (ctx.source() != null)
                ? agentAI.process(ctx.userId(), ctx.sessionId(), ctx.message(), ctx.source(), ctx.fileName())
                : agentAI.process(ctx.userId(), ctx.sessionId(), ctx.message());

        return agentStream
                .doOnNext(item -> {
                    if (firstAt.get() == 0L) {
                        firstAt.set(System.currentTimeMillis());
                        log.info("[Chat] 首token延迟 --> {}ms", firstAt.get() - startAt);
                    }
                    deltaCount.incrementAndGet();
                    // 不能用 isNotBlank 过滤：AgentScope 流式 delta 包含独立的空白/换行分片（" ", "\n", "\n\n"），
                    // 过滤会导致持久化的助手消息丢失所有空白，渲染时标题吞掉后续列表行。
                    // 仅排除 null；空串/纯空白均透传给 reply 缓冲，与前端 SSE 累积语义保持一致。
                    if (item != null) {
                        reply.appendAnswer(item);
                    }
                })
                .map(item -> {
                    log.debug("【Agent服务】输出详情: {}", item);
                    return Result.success(ChatVO.builder().reply(item).timestamp(startAt).build());
                })
                .doOnComplete(() -> {
                    long total = System.currentTimeMillis() - startAt;
                    long first = firstAt.get() == 0L ? -1 : firstAt.get() - startAt;
                    log.info("[Chat] 完成: 总耗时 --> {}ms, 首token --> {}ms, delta数 --> {}",
                            total, first, deltaCount.get());
                })
                // 兜底：整轮未产生任何文本分片（模型空输出 / 会话状态脏导致空转）时，
                // 在流末尾追加一条「仅展示、不落库」的占位回复（emptyReply=true），
                // 避免前端落到「（已停止生成）」误导用户以为自己点了停止。
                // 该提示不写入 reply 缓冲，故 AbstractChatStrategy 不会将其落库。
                .concatWith(Mono.defer(() -> {
                    if (deltaCount.get() == 0L) {
                        return Mono.just(Result.success(ChatVO.builder()
                                .reply(EMPTY_REPLY_HINT).timestamp(startAt).emptyReply(true).build()));
                    }
                    return Mono.empty();
                }));
    }

    /** 模型未返回任何文本时的占位提示（仅展示，不计入对话内容落库） */
    private static final String EMPTY_REPLY_HINT = "（智能体未返回内容，可重新发送这条消息试试）";
}
