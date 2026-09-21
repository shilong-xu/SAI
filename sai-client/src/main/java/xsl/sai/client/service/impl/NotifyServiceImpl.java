package xsl.sai.client.service.impl;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.NotifyMessageEntity;
import xsl.sai.client.mapper.NotifyMessageMapper;
import xsl.sai.client.pojo.vo.NotifyMessageVO;
import xsl.sai.client.service.NotifyService;
import xsl.sai.framework.client.SseClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 站内消息服务实现
 *
 * <p>推送链路：{@code push()} 先落库保证不丢消息，再通过 {@link SseClient} 实时推送；
 * 用户不在线时推送自然丢弃，下次打开页面拉列表仍能看到。
 *
 * @author SAI
 */
@Service
public class NotifyServiceImpl implements NotifyService {

    /** SSE 事件名：前端 addEventListener('notify', ...) 监听 */
    public static final String SSE_EVENT = "notify";

    /** 增量同步最多返回条数（够覆盖一次断线窗口即可，超出的等下一轮 sync 或打开面板拉全量） */
    private static final int SYNC_LIMIT = 20;

    private final NotifyMessageMapper mapper;

    public NotifyServiceImpl(NotifyMessageMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 落库并推送一条消息。
     *
     * <p>{@code userId} 为 null 时视为<b>全员广播</b>：user_id 存 NULL，
     * 所有用户的列表/未读数均包含该消息（已读状态全局共享）。
     */
    @Override
    public Mono<NotifyMessageVO> push(String userId, String type, String title, String content, String link) {
        NotifyMessageEntity e = new NotifyMessageEntity();
        e.setUserId(userId);
        e.setType(normalizeType(type));
        e.setTitle(title);
        e.setContent(content);
        e.setLink(link);
        e.setReadFlag(false);
        e.setIsDelete(false);
        e.setNewEntity(true);   // 标记新建，save() 走 INSERT；时间由 EntityAuditCallback 填充

        return mapper.save(e)
                .map(NotifyServiceImpl::toVO)
                // 落库成功后再推，前端拿到的一定是带 id 的完整消息
                .doOnNext(vo -> SseClient.sendTo(userId, SSE_EVENT, vo));
    }

    @Override
    public Flux<NotifyMessageVO> list(String userId, int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        return mapper.listByUser(userId, size).map(NotifyServiceImpl::toVO);
    }

    @Override
    public Mono<Long> unreadCount(String userId) {
        return mapper.countUnread(userId).defaultIfEmpty(0L);
    }

    @Override
    public Mono<Boolean> read(String userId, Long id) {
        return mapper.markRead(id, userId)
                .defaultIfEmpty(0)
                .map(n -> n != null && n > 0);
    }

    @Override
    public Mono<Integer> readAll(String userId) {
        return mapper.markAllRead(userId).defaultIfEmpty(0);
    }

    @Override
    public Mono<Map<String, Object>> sync(String userId, long afterId) {
        Mono<Long> count = mapper.countUnread(userId).defaultIfEmpty(0L);
        Mono<List<NotifyMessageVO>> missed = mapper.listAfter(userId, afterId, SYNC_LIMIT)
                .map(NotifyServiceImpl::toVO)
                .collectList();
        return Mono.zip(count, missed).map(t -> {
            Map<String, Object> r = new HashMap<>(4);
            r.put("count", t.getT1());
            r.put("missed", t.getT2());
            return r;
        });
    }

    /** 消息类型归一：仅保留约定的四种，未识别的一律 info */
    private static String normalizeType(String type) {
        if (type == null) {
            return "info";
        }
        String t = type.trim().toLowerCase();
        return switch (t) {
            case "success", "warn", "error" -> t;
            default -> "info";
        };
    }

    private static NotifyMessageVO toVO(NotifyMessageEntity e) {
        return NotifyMessageVO.builder()
                .id(e.getId())
                .type(e.getType())
                .title(e.getTitle())
                .content(e.getContent())
                .link(e.getLink())
                .read(Boolean.TRUE.equals(e.getReadFlag()))
                .createTime(e.getCreateTime())
                .build();
    }
}
