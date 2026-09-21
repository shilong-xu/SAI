package xsl.sai.client.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xsl.sai.client.pojo.dto.ChatDTO;

import java.util.List;

/**
 * 对话模式策略工厂：按 {@link ChatDTO} 状态从已注册策略中选出适用者，替换 Controller / Service 中的
 * if-else 分派。各策略的 {@code supports} 互斥（SAA / 思考追踪二选一，其余归普通对话），至多命中一个。
 */
@Slf4j
@Component
public class ChatModeStrategyFactory {

    private final List<ChatModeStrategy> strategies;

    public ChatModeStrategyFactory(List<ChatModeStrategy> strategies) {
        this.strategies = strategies;
    }

    public ChatModeStrategy resolve(ChatDTO dto) {
        return strategies.stream()
                .filter(s -> s.supports(dto))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到匹配的对话模式策略: " + dto));
    }
}
