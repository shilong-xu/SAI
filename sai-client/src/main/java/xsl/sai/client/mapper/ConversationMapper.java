package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import xsl.sai.client.domain.ClientConversationEntity;
import xsl.sai.client.pojo.vo.ConversationVO;

/**
 * 会话 Mapper —— 基于 Spring Data R2DBC
 * <p>数据库操作优先级：
 * ① {@link ReactiveCrudRepository} 自带方法 / 派生查询；
 * ② 复杂 SQL 用 {@code @Query} 在本 Mapper 内声明（如下 {@link #listByUser}）；
 * ③ 无法映射的场景（向量等）才用 {@code R2dbcClient}。
 *
 * @author SAI
 */
public interface ConversationMapper extends ReactiveCrudRepository<ClientConversationEntity, String> {

    /**
     * 查询用户会话列表并关联统计消息数（DTO 投影到 {@link ConversationVO}，列别名用 snake_case 供命名策略映射）
     */
    @Query("SELECT c.id AS id, c.user_id AS user_id, c.title AS title, c.model AS model, "
            + "c.is_pinned AS is_pinned, c.remark AS remark, "
            + "DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i:%s') AS create_time, "
            + "DATE_FORMAT(c.update_time, '%Y-%m-%d %H:%i:%s') AS update_time, "
            + "CAST((SELECT COUNT(*) FROM client_message m WHERE m.conversation_id = c.id AND m.is_delete = 0) AS SIGNED) AS message_count "
            + "FROM client_conversation c "
            + "WHERE c.user_id = :userId AND c.is_delete = 0 "
            + "ORDER BY c.is_pinned DESC, c.update_time DESC")
    Flux<ConversationVO> listByUser(@Param("userId") String userId);
}
