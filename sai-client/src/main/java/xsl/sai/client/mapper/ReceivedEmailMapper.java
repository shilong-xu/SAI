package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.ReceivedEmailEntity;

import java.time.LocalDateTime;

/**
 * 邮件 Mapper（收 + 发统一记录）—— 基于 Spring Data R2DBC
 * <p>邮件仅映射业务列；原 status / process_result / embedding_vec 列已从表中移除（不再做 Agent 处理与向量化）。
 *
 * @author SAI
 */
public interface ReceivedEmailMapper extends ReactiveCrudRepository<ReceivedEmailEntity, Long> {

    /** 覆盖继承的 findById：显式列出列、排除 embedding_vec */
    @Query("SELECT id, user_id, direction, message_id, from_addr, from_name, to_addr, subject, content, summary, "
            + "has_attachment, attach_count, received_at, remark, "
            + "create_time, update_time, is_delete FROM received_email WHERE id = :id")
    @NonNull Mono<ReceivedEmailEntity> findById(@NonNull Long id);

    /**
     * 关键词（主题 / 正文 / 发件人）分页查询
     *
     * @param kw        已含通配符的 LIKE 模式（为空表示不按关键词过滤）
     * @param direction 邮件方向（为空表示不限方向）
     */
    @Query("SELECT id, user_id, direction, message_id, from_addr, from_name, to_addr, subject, content, summary, "
            + "has_attachment, attach_count, received_at, remark, "
            + "create_time, update_time, is_delete FROM received_email "
            + "WHERE is_delete = 0 AND (:direction IS NULL OR direction = :direction) "
            + "AND (:kw IS NULL OR CONCAT(subject, ' ', IFNULL(content, ''), ' ', IFNULL(from_addr, '')) LIKE :kw) "
            + "ORDER BY create_time DESC LIMIT :size OFFSET :offset")
    Flux<ReceivedEmailEntity> page(@Param("kw") String kw, @Param("direction") Integer direction,
                                   @Param("size") int size, @Param("offset") int offset);

    /** 关键词计数（direction 为空表示不限方向） */
    @Query("SELECT COUNT(*) FROM received_email "
            + "WHERE is_delete = 0 AND (:direction IS NULL OR direction = :direction) "
            + "AND (:kw IS NULL OR CONCAT(subject, ' ', IFNULL(content, ''), ' ', IFNULL(from_addr, '')) LIKE :kw)")
    Mono<Long> countByKw(@Param("kw") String kw, @Param("direction") Integer direction);

    /** 按 Message-ID 去重查询（返回存在的记录主键；无则 empty）。仅用于接收方向。 */
    @Query("SELECT id FROM received_email WHERE message_id = :mid AND is_delete = 0 LIMIT 1")
    Mono<Long> findIdByMessageId(@Param("mid") String mid);

    /** 按方向累计计数（0-收件 1-发件），供仪表盘统计 */
    @Query("SELECT COUNT(*) FROM received_email WHERE is_delete = 0 AND direction = :direction")
    Mono<Long> countByDirection(@Param("direction") Integer direction);

    /** 未摘要收件计数（direction = 0 且 summary 为 NULL；Agent 处理取消后 summary 不会被回填） */
    @Query("SELECT COUNT(*) FROM received_email WHERE is_delete = 0 AND direction = 0 AND summary IS NULL")
    Mono<Long> countPending();

    /** 区间计数（收 + 发），按入库时间统计 */
    @Query("SELECT COUNT(*) FROM received_email WHERE is_delete = 0 "
            + "AND create_time >= :start AND create_time < :end")
    Mono<Long> countBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
