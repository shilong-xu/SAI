package xsl.sai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Alibaba 编排层配置。
 *
 * <p>定位：AgentScope Java 负责构建「核心智能体」（记忆 / 技能 / 工具 / 计划模式），
 * Spring AI Alibaba 负责「顶层流程编排」（Graph 多智能体协作）。本配置把二者共用的
 * LLM 客户端以 OpenAI 兼容方式接入（复用现有 agentScope.models.* 端点）。
 */
@Configuration
public class SaaConfig {

    @Bean
    @org.springframework.context.annotation.Primary
    public ChatClient orchestrationChatClient(
            @Value("${agentScope.models.apiKey}") String apiKey,
            @Value("${agentScope.models.apiBase}") String apiBase,
            @Value("${agentScope.models.modelName}") String modelName,
            @Value("${agentScope.models.temperature:0.6}") Double temperature) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(apiBase)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).build();
    }
}
