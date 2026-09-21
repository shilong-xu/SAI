package xsl.sai.client.service.impl;

import io.agentscope.core.message.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.service.AgentAIService;
import xsl.sai.client.service.chat.ChatModeStrategyFactory;
import xsl.sai.framework.result.Result;

/**
 * &#064;DATE: 2026/6/15 19:44
 * &#064;AUTHOR: XSL
 *
 */
@Slf4j
@Service
public class AgentAIServiceImpl implements AgentAIService {

    private final ChatModeStrategyFactory strategyFactory;

    public AgentAIServiceImpl(ChatModeStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    @Override
    public Flux<Result> chat(ChatDTO chatDTO) {
        return strategyFactory.resolve(chatDTO).execute(chatDTO, null, null);
    }

    @Override
    public Flux<Result> chat(ChatDTO chatDTO, Source source, String fileName) {
        return strategyFactory.resolve(chatDTO).execute(chatDTO, source, fileName);
    }

}
