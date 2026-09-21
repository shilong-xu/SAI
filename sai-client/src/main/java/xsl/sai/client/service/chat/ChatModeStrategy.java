package xsl.sai.client.service.chat;

import io.agentscope.core.message.Source;
import reactor.core.publisher.Flux;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.framework.result.Result;

/**
 * 对话模式策略：每一种对话模式（普通对话 / 思考追踪 / SAA 编排）实现同一契约，
 * 由 {@link ChatModeStrategyFactory} 按 {@link ChatDTO} 状态选择，替换原先在
 * Controller / Service 中的 if-else 分派，避免堆代码。
 */
public interface ChatModeStrategy {

    /** 执行该模式的对话流水线，返回 SSE 流 */
    Flux<Result> execute(ChatDTO dto, Source source, String fileName);

    /** 该策略是否适用于给定的对话参数 */
    boolean supports(ChatDTO dto);
}
