package xsl.sai.client.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * &#064;DATE: 2026/6/15 19:29
 * &#064;AUTHOR: XSL
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDTO {

    /**
     * 会话ID（前端创建会话后回传，用于消息持久化与 Agent 会话上下文）
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID（Agent 会话上下文 key，默认等同 conversationId）
     */
    private String sessionId;

    /**
     * 文字内容
     */
    private String message;

    /**
     * 思考追踪模式（AgentScope 自驱动 ReAct 循环）：true 时分发给 agentAIService.orchestrate
     */
    private boolean loopMode = false;

    /**
     * SAA 编排模式（Spring AI Alibaba StateGraph 静态流水线）：true 时分发给 agentAIService.saaOrchestrate
     */
    private boolean saaMode = false;

}
