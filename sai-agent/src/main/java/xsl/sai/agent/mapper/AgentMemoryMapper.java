package xsl.sai.agent.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.domain.AgentMemoryEntity;

/**
 * 长期记忆 Mapper —— 基于 Spring Data R2DBC
 *
 * <p>说明：向量列的写入需 {@code STRING_TO_VECTOR} 转换，无法用 {@code save()} 直接完成，
 * 统一走 {@code R2dbcClient} 原生 SQL（见 {@code AgentMemoryServiceImpl}）。
 * 本接口只承担结构化查询（列表、计数、去重判定等）。
 *
 * @author SAI
 */
public interface AgentMemoryMapper extends ReactiveCrudRepository<AgentMemoryEntity, Long> {

    /** 按用户查询未删除记忆，按 id 倒序 */
    Flux<AgentMemoryEntity> findByUserIdAndIsDeleteFalseOrderByIdDesc(String userId);

    /** 按用户 + 类型查询未删除记忆 */
    Flux<AgentMemoryEntity> findByUserIdAndMemoryTypeAndIsDeleteFalseOrderByIdDesc(String userId, String memoryType);

    /** 统计某用户的记忆条数 */
    @Query("SELECT COUNT(*) FROM agent_memory WHERE user_id = :userId AND is_delete = 0")
    Mono<Long> countByUserId(String userId);

    /** 统计缺失向量（embedding_vec 为 NULL）且未删除的记忆条数，供定时补全任务观测进度 */
    @Query("SELECT COUNT(*) FROM agent_memory WHERE embedding_vec IS NULL AND is_delete = 0")
    Mono<Long> countMissingEmbedding();

    /** 按来源会话统计（去重判定 / 运维排查用） */
    @Query("SELECT COUNT(*) FROM agent_memory WHERE session_id = :sessionId AND is_delete = 0")
    Mono<Long> countBySessionId(String sessionId);
}
