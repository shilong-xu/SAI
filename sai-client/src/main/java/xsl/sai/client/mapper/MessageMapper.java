package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.ClientMessageEntity;
import xsl.sai.client.mapper.projection.DailyStatProjection;

import java.time.LocalDateTime;

/**
 * 消息 Mapper —— 基于 Spring Data R2DBC
 * <p>数据库操作优先级：
 * ① {@link ReactiveCrudRepository} 自带方法 / 派生查询（本类）；
 * ② 复杂 SQL 用 {@code @Query} 在 Mapper 内声明；
 * ③ 无法映射的场景（向量等）才用 {@code R2dbcClient}。
 *
 * @author SAI
 */
public interface MessageMapper extends ReactiveCrudRepository<ClientMessageEntity, Long> {

    /** 统计会话内未删除消息数（派生查询） */
    Mono<Long> countByConversationIdAndIsDeleteFalse(String conversationId);

    /** 按会话查询未删除消息，按 id 升序（派生查询） */
    Flux<ClientMessageEntity> findByConversationIdAndUserIdAndIsDeleteFalseOrderByIdAsc(String conversationId, String userId);

    /** 汇总时间段内未删除消息的 token 数（start 含 / end 不含） */
    @Query("SELECT IFNULL(SUM(tokens), 0) FROM client_message WHERE is_delete = 0 AND create_time >= :start AND create_time < :end")
    Mono<Long> sumTokensBetween(LocalDateTime start, LocalDateTime end);

    /** 汇总全部未删除消息的 token 数 */
    @Query("SELECT IFNULL(SUM(tokens), 0) FROM client_message WHERE is_delete = 0")
    Mono<Long> sumTokensAll();

    /** 自 start（含）起按日汇总 token 消耗 */
    @Query("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDate, IFNULL(SUM(tokens), 0) AS statCount "
            + "FROM client_message WHERE is_delete = 0 AND create_time >= :start "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')")
    Flux<DailyStatProjection> sumTokensDaily(LocalDateTime start);
}
