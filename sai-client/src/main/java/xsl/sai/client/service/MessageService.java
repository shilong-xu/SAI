package xsl.sai.client.service;

import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.MessageVO;

import java.util.List;

/**
 * 会话消息记录服务
 *
 * @author SAI
 */
public interface MessageService {

    /** 保存用户消息 */
    Mono<Void> saveUserMessage(String conversationId, String userId, String content);

    /** 保存助手消息 */
    Mono<Void> saveAssistantMessage(String conversationId, String userId, String content);

    /**
     * 保存助手消息并记录 token 用量
     *
     * @param tokens 本次回复消耗的 token 数（近似值，如回复文本字符数 / 分片数）
     */
    Mono<Void> saveAssistantMessage(String conversationId, String userId, String content, int tokens);

    /** 统计会话内消息数 */
    Mono<Long> countByConversation(String conversationId);

    /** 查询会话下全部消息（按时间正序） */
    Mono<List<MessageVO>> listByConversation(String conversationId, String userId);
}
