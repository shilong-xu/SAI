package xsl.sai.client.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.ClientConversationEntity;
import xsl.sai.client.pojo.vo.ConversationVO;
import xsl.sai.client.mapper.ConversationMapper;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;

import java.util.List;

/**
 * 会话列表服务实现
 * <p>简单 CRUD 使用 {@link ConversationMapper} 自带方法；
 * 关联统计等复杂 SQL 用 Mapper 内 {@code @Query} 声明（DTO 投影）。
 *
 * @author SAI
 */
@Slf4j
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageService messageService;

    public ConversationServiceImpl(ConversationMapper conversationMapper, MessageService messageService) {
        this.conversationMapper = conversationMapper;
        this.messageService = messageService;
    }

    @Override
    public Mono<ConversationVO> create(String userId, String title, String model) {
        String id = IdUtil.fastSimpleUUID();
        String finalTitle = StrUtil.isNotBlank(title) ? title : "新对话";
        ClientConversationEntity entity = new ClientConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(finalTitle);
        entity.setModel(model);
        entity.setIsPinned(0);
        entity.setNewEntity(true); // 标记为新建，save() 走 INSERT；时间由 EntityAuditCallback 自动填充
        return conversationMapper.save(entity)
                .map(saved -> ConversationVO.builder()
                        .id(saved.getId())
                        .userId(saved.getUserId())
                        .title(saved.getTitle())
                        .model(saved.getModel())
                        .isPinned(saved.getIsPinned())
                        .messageCount(0L)
                        .build());
    }

    @Override
    public Mono<List<ConversationVO>> listByUser(String userId) {
        // 关联统计消息数：复杂 SQL 已下沉到 ConversationMapper.listByUser（@Query + DTO 投影）
        return conversationMapper.listByUser(userId).collectList();
    }

    @Override
    public Mono<Void> rename(String conversationId, String title) {
        return conversationMapper.findById(conversationId)
                .flatMap(entity -> {
                    entity.setTitle(title);
                    return conversationMapper.save(entity); // updateTime 由 EntityAuditCallback 自动刷新
                })
                .then();
    }

    @Override
    public Mono<Void> pin(String conversationId, Integer isPinned) {
        return conversationMapper.findById(conversationId)
                .flatMap(entity -> {
                    entity.setIsPinned(isPinned == null ? 0 : isPinned);
                    return conversationMapper.save(entity); // updateTime 由 EntityAuditCallback 自动刷新
                })
                .then();
    }

    @Override
    public Mono<Void> softDelete(String conversationId, String userId) {
        return conversationMapper.findById(conversationId)
                .filter(entity -> entity.getUserId() != null && entity.getUserId().equals(userId))
                .flatMap(entity -> {
                    entity.setIsDelete(true);
                    return conversationMapper.save(entity); // updateTime 由 EntityAuditCallback 自动刷新
                })
                .then();
    }

    @Override
    public Mono<Void> autoTitleIfFirst(String conversationId, String message) {
        return messageService.countByConversation(conversationId)
                .filter(cnt -> cnt != null && cnt == 1)
                .flatMap(cnt -> {
                    String title = message.length() > 20 ? message.substring(0, 20) + "…" : message;
                    return rename(conversationId, title);
                })
                .then();
    }
}
