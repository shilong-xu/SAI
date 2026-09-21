package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.NotifyMessageEntity;

/**
 * 站内消息 Mapper —— 基于 Spring Data R2DBC
 *
 * <p>所有查询均按 {@code user_id} 隔离（广播消息 {@code user_id IS NULL} 对所有用户可见），
 * 且显式列出列（不 SELECT *）。
 *
 * <p>注意：广播消息的已读状态是全局共享的（任一用户标记已读即全员已读），
 * 适合定时任务执行情况这类系统级通知，不适合按人区分的强提醒。
 *
 * @author SAI
 */
public interface NotifyMessageMapper extends ReactiveCrudRepository<NotifyMessageEntity, Long> {

    /** 覆盖继承的 findById：显式列出列 */
    @Query("SELECT id, user_id, type, title, content, link, read_flag, create_time, update_time, is_delete "
            + "FROM notify_message WHERE id = :id")
    @NonNull Mono<NotifyMessageEntity> findById(@NonNull Long id);

    /** 最近消息（未读优先，再按时间倒序；含全员广播消息） */
    @Query("SELECT id, user_id, type, title, content, link, read_flag, create_time, update_time, is_delete "
            + "FROM notify_message "
            + "WHERE is_delete = 0 AND (user_id = :userId OR user_id IS NULL) "
            + "ORDER BY read_flag ASC, id DESC LIMIT :limit")
    Flux<NotifyMessageEntity> listByUser(@Param("userId") String userId, @Param("limit") int limit);

    /** 未读数（含全员广播消息） */
    @Query("SELECT COUNT(*) FROM notify_message "
            + "WHERE is_delete = 0 AND (user_id = :userId OR user_id IS NULL) AND read_flag = 0")
    Mono<Long> countUnread(@Param("userId") String userId);

    /** 增量同步：取 id 大于 afterId 的消息（新到优先，最多 limit 条），供刷新/重连后补偿错过的推送 */
    @Query("SELECT id, user_id, type, title, content, link, read_flag, create_time, update_time, is_delete "
            + "FROM notify_message "
            + "WHERE is_delete = 0 AND (user_id = :userId OR user_id IS NULL) AND id > :afterId "
            + "ORDER BY id DESC LIMIT :limit")
    Flux<NotifyMessageEntity> listAfter(@Param("userId") String userId,
                                        @Param("afterId") long afterId,
                                        @Param("limit") int limit);

    /** 单条已读（返回受影响行数，0 表示不是自己的消息或已读过） */
    @Modifying
    @Query("UPDATE notify_message SET read_flag = 1, update_time = NOW() "
            + "WHERE id = :id AND (user_id = :userId OR user_id IS NULL) AND read_flag = 0 AND is_delete = 0")
    Mono<Integer> markRead(@Param("id") Long id, @Param("userId") String userId);

    /** 全部已读（含全员广播消息，标记后对全部用户生效） */
    @Modifying
    @Query("UPDATE notify_message SET read_flag = 1, update_time = NOW() "
            + "WHERE (user_id = :userId OR user_id IS NULL) AND read_flag = 0 AND is_delete = 0")
    Mono<Integer> markAllRead(@Param("userId") String userId);
}
