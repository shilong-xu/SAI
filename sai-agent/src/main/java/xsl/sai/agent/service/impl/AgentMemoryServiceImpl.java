package xsl.sai.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.domain.AgentMemoryEntity;
import xsl.sai.agent.memory.AgentMemoryHit;
import xsl.sai.agent.mapper.AgentMemoryMapper;
import xsl.sai.agent.service.AgentMemoryService;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.framework.util.VectorUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 长期记忆服务实现
 *
 * <p><b>为什么不用 {@code ReactiveCrudRepository.save()}</b>：向量列需经
 * {@code STRING_TO_VECTOR(:vec)} 转换，只能拼原生 SQL——与 {@code knowledge_chunk} 的写法保持一致。
 *
 * <p><b>向量召回</b>：相似度计算由 myvector 插件的 {@code myvector_distance} UDF 下推到数据库完成，
 * 应用层只负责组装查询向量与阈值。
 *
 * @author SAI
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final String TABLE = "agent_memory";
    private static final String VECTOR_COLUMN = "embedding_vec";

    private final R2dbcClient r2dbcClient;
    private final AgentMemoryMapper agentMemoryMapper;

    @Override
    public Mono<Long> saveMemory(AgentMemoryEntity entity) {
        if (entity == null || entity.getContent() == null || entity.getContent().isBlank()) {
            return Mono.error(new IllegalArgumentException("记忆内容不能为空"));
        }
        Map<String, Object> p = new LinkedHashMap<>(10);
        p.put("userId", nullToEmpty(entity.getUserId()));
        p.put("agentName", entity.getAgentName() == null ? "Master" : entity.getAgentName());
        p.put("sessionId", entity.getSessionId());
        p.put("memoryType", entity.getMemoryType() == null ? "FACT" : entity.getMemoryType());
        p.put("content", entity.getContent());
        p.put("source", entity.getSource() == null ? "RAW" : entity.getSource());
        p.put("importance", entity.getImportance() == null ? 3 : entity.getImportance());

        String sql;
        if (entity.getEmbedding() != null && entity.getEmbedding().length > 0) {
            p.put("vec", VectorUtils.toVecJson(entity.getEmbedding()));
            sql = "INSERT INTO " + TABLE + " "
                    + "(user_id, agent_name, session_id, memory_type, content, source, importance, "
                    + "embedding_vec, access_count, is_delete) "
                    + "VALUES (:userId, :agentName, :sessionId, :memoryType, :content, :source, :importance, "
                    + "STRING_TO_VECTOR(:vec), 0, 0)";
        } else {
            // 无向量（如 embedding 服务不可用）时仍落文本，保证记忆不丢，只是暂不可语义检索
            sql = "INSERT INTO " + TABLE + " "
                    + "(user_id, agent_name, session_id, memory_type, content, source, importance, "
                    + "access_count, is_delete) "
                    + "VALUES (:userId, :agentName, :sessionId, :memoryType, :content, :source, :importance, 0, 0)";
        }
        return r2dbcClient.executeReturnId(sql, p)
                .doOnError(e -> log.warn("[Memory] 写入长期记忆失败: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Flux<AgentMemoryHit> vectorRecall(String userId, float[] queryVec, int topK, double threshold) {
        if (queryVec == null || queryVec.length == 0 || topK <= 0) {
            return Flux.empty();
        }
        Map<String, Object> where = new LinkedHashMap<>(2);
        where.put("userId", nullToEmpty(userId));
        return r2dbcClient.vectorSearch(
                        queryVec,
                        VECTOR_COLUMN,
                        "id, content, memory_type, source",
                        TABLE,
                        "user_id = :userId AND is_delete = 0",
                        where,
                        topK,
                        threshold,
                        Map.class)
                .map(row -> toHit((Map<String, Object>) row))
                .doOnError(e -> log.warn("[Memory] 向量召回失败: {}", e.getMessage()));
    }

    @Override
    public Mono<Integer> touchAccess(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.just(0);
        }
        // id 为数字，直接拼 IN 列表安全（R2dbcClient 命名参数不支持集合展开）
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "UPDATE " + TABLE + " SET access_count = IFNULL(access_count, 0) + 1, "
                + "last_access_time = NOW() WHERE id IN (" + in + ")";
        return r2dbcClient.execute(sql, Map.of()).map(Long::intValue);
    }

    @Override
    public Mono<Long> countByUserId(String userId) {
        return agentMemoryMapper.countByUserId(nullToEmpty(userId));
    }

    @Override
    public Flux<AgentMemoryEntity> findMissingEmbedding(int limit) {
        if (limit <= 0) {
            return Flux.empty();
        }
        // 只取回填所需的 id + content；embedding_vec 为 NULL 表示向量化失败漏写，需定时重试补全
        String sql = "SELECT id, content FROM " + TABLE
                + " WHERE embedding_vec IS NULL AND is_delete = 0"
                + " ORDER BY id ASC LIMIT " + limit;
        return r2dbcClient.queryList(sql, Map.of(), AgentMemoryEntity.class)
                .doOnError(e -> log.warn("[Memory] 拉取缺失向量记忆失败: {}", e.getMessage()));
    }

    @Override
    public Mono<Long> countMissingEmbedding() {
        return agentMemoryMapper.countMissingEmbedding();
    }

    @Override
    public Mono<Integer> fillEmbedding(Long id, float[] vec) {
        if (id == null || vec == null || vec.length == 0) {
            return Mono.just(0);
        }
        Map<String, Object> p = new LinkedHashMap<>(2);
        p.put("id", id);
        p.put("vec", VectorUtils.toVecJson(vec));
        String sql = "UPDATE " + TABLE + " SET embedding_vec = STRING_TO_VECTOR(:vec) WHERE id = :id";
        return r2dbcClient.execute(sql, p)
                .map(Long::intValue)
                .doOnError(e -> log.warn("[Memory] 回填记忆向量失败 id={}: {}", id, e.getMessage()));
    }

    // ==================== 私有辅助 ====================

    private AgentMemoryHit toHit(Map<String, Object> row) {
        AgentMemoryHit hit = new AgentMemoryHit();
        hit.setId(VectorUtils.toLong(row.get("id")));
        hit.setContent(str(row.get("content")));
        hit.setMemoryType(str(row.get("memory_type")));
        hit.setSource(str(row.get("source")));
        hit.setScore(toDouble(row.get("__score")));
        return hit;
    }

    private static double toDouble(Object v) {
        if (v == null) {
            return 0d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
