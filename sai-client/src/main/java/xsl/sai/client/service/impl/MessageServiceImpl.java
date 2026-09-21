package xsl.sai.client.service.impl;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.ClientMessageEntity;
import xsl.sai.client.pojo.vo.MessageVO;
import xsl.sai.client.mapper.MessageMapper;
import xsl.sai.client.service.MessageService;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 会话消息记录服务实现
 * <p>全部走 {@link MessageMapper}：保存用自带 save；统计与按会话查询用派生查询。
 *
 * @author SAI
 */
@Service
public class MessageServiceImpl implements MessageService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MessageMapper messageMapper;

    public MessageServiceImpl(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public Mono<Void> saveUserMessage(String conversationId, String userId, String content) {
        return saveMessage(conversationId, userId, "user", content, 0);
    }

    @Override
    public Mono<Void> saveAssistantMessage(String conversationId, String userId, String content) {
        return saveMessage(conversationId, userId, "assistant", content, 0);
    }

    @Override
    public Mono<Void> saveAssistantMessage(String conversationId, String userId, String content, int tokens) {
        return saveMessage(conversationId, userId, "assistant", content, tokens);
    }

    private Mono<Void> saveMessage(String conversationId, String userId, String role, String content, int tokens) {
        ClientMessageEntity entity = new ClientMessageEntity();
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setTokens(tokens);
        entity.setNewEntity(true);
        return messageMapper.save(entity).then();
    }

    @Override
    public Mono<Long> countByConversation(String conversationId) {
        return messageMapper.countByConversationIdAndIsDeleteFalse(conversationId);
    }

    @Override
    public Mono<List<MessageVO>> listByConversation(String conversationId, String userId) {
        return messageMapper.findByConversationIdAndUserIdAndIsDeleteFalseOrderByIdAsc(conversationId, userId)
                .map(this::toVO)
                .collectList();
    }

    private MessageVO toVO(ClientMessageEntity e) {
        return MessageVO.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .role(e.getRole())
                .content(e.getContent())
                .tokens(e.getTokens())
                .createTime(e.getCreateTime() == null ? null : e.getCreateTime().format(TIME_FMT))
                .build();
    }
}
