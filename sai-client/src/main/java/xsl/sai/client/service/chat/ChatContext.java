package xsl.sai.client.service.chat;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.message.Source;
import xsl.sai.client.pojo.dto.ChatDTO;

/**
 * 单轮对话的上下文载体：统一解析 userId / sessionId / conversationId / message，
 * 并携带可选的文件源（Source）与文件名，供各模式策略共享，消除重复解析逻辑。
 */
public record ChatContext(
        String conversationId,
        String sessionId,
        String userId,
        String message,
        Source source,
        String fileName) {

    public static ChatContext from(ChatDTO dto, String userId) {
        String conversationId = StrUtil.isNotBlank(dto.getConversationId()) ? dto.getConversationId() : null;
        // 会话ID 同时作为 Agent 的 sessionId，使模型上下文与会话一一对应
        String sessionId = conversationId != null ? conversationId
                : (StrUtil.isNotBlank(dto.getSessionId()) ? dto.getSessionId() : "default");
        return new ChatContext(conversationId, sessionId,
                StrUtil.isNotBlank(userId) ? userId : "default",
                dto.getMessage(), null, null);
    }

    public ChatContext withSource(Source source, String fileName) {
        return new ChatContext(conversationId, sessionId, userId, message, source, fileName);
    }

    /** 是否为已存在会话（会话ID 非空才落库） */
    public boolean hasConversation() {
        return conversationId != null;
    }
}
