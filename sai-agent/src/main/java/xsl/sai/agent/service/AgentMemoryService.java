package xsl.sai.agent.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.domain.AgentMemoryEntity;
import xsl.sai.agent.memory.AgentMemoryHit;

import java.util.Collection;

/**
 * Agent 长期记忆服务
 *
 * <p>职责边界：只做「存储 + 向量召回」，不负责决定何时写入 / 何时检索——
 * 那属于 {@code LongTermMemory} 实现与 Hook 的职责。
 *
 * @author SAI
 */
public interface AgentMemoryService {

    /**
     * 写入一条长期记忆（含向量）。
     *
     * @param entity 记忆实体，{@code embedding} 为 null 时只写文本不写向量
     * @return 新记录主键
     */
    Mono<Long> saveMemory(AgentMemoryEntity entity);

    /**
     * 按向量相似度召回长期记忆（余弦，越大越相关）。
     *
     * @param userId    用户隔离维度
     * @param queryVec  查询向量
     * @param topK      返回条数
     * @param threshold 相似度下限 [0,1]
     */
    Flux<AgentMemoryHit> vectorRecall(String userId, float[] queryVec, int topK, double threshold);

    /**
     * 递增被召回次数并刷新最近召回时间（用于记忆热度观测）。
     *
     * @param ids 被召回的记忆主键
     * @return 更新行数
     */
    Mono<Integer> touchAccess(Collection<Long> ids);

    /**
     * 拉取缺失向量（embedding_vec 为 NULL 且未删除）的长期记忆，供定时补全任务回填。
     * 仅查询回填所需的 id + content。
     *
     * @param limit 单次最多拉取条数（0 或负数返回空）
     */
    Flux<AgentMemoryEntity> findMissingEmbedding(int limit);

    /**
     * 统计仍缺失向量（embedding_vec 为 NULL）的长期记忆条数，用于补全任务进度观测。
     */
    Mono<Long> countMissingEmbedding();

    /**
     * 回填单条记忆的向量（embedding_vec = STRING_TO_VECTOR(:vec)）。
     *
     * @param id  记忆主键
     * @param vec 向量（bge-m3 1024 维）
     * @return 更新行数
     */
    Mono<Integer> fillEmbedding(Long id, float[] vec);

    /**
     * 统计某用户的记忆条数（健康检查 / 运维用）
     */
    Mono<Long> countByUserId(String userId);
}
