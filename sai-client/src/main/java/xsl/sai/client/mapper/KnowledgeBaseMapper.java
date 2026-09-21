package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.KnowledgeBaseEntity;

/**
 * 知识库条目仓储
 *
 * @author SAI
 */
@Repository
public interface KnowledgeBaseMapper extends R2dbcRepository<KnowledgeBaseEntity, Long> {

    /** 覆盖 findById：SELECT * 即可（knowledge_base 不含 VECTOR 列，无需排除） */
    @Query("SELECT * FROM knowledge_base WHERE id = :id AND is_delete = 0")
    Mono<KnowledgeBaseEntity> findActiveById(@Param("id") Long id);

    /**
     * 关键词模糊分页（按更新时间倒序）。
     *
     * <p>命中规则：<b>正文</b>，或该条目已提炼的<b>关键词</b>（{@code knowledge_chunk.keyword}）任一 LIKE 命中即算命中。
     * 之所以要连关键词一起搜：知识库的关键词是向量索引的检索入口，存在「关键词里有、正文未逐字出现」的词
     * （如正文写 Spring Boot 版本号，关键词是 Spring Boot 3），只搜正文会漏。
     *
     * <p>{@code kw} 为 {@code null} 时不过滤，等价于全量分页 —— 无搜索态复用同一条 SQL，少一个方法。
     *
     * @param kw 已含通配符的 LIKE 模式（如 {@code %向量%}），为 null 表示不按关键词过滤
     */
    @Query("SELECT * FROM knowledge_base "
            + "WHERE is_delete = 0 "
            + "AND (:kw IS NULL OR content LIKE :kw "
            + "     OR id IN (SELECT base_id FROM knowledge_chunk "
            + "                WHERE is_delete = 0 AND keyword LIKE :kw)) "
            + "ORDER BY update_time DESC, id DESC LIMIT :size OFFSET :offset")
    Flux<KnowledgeBaseEntity> pageFiltered(@Param("kw") String kw,
                                          @Param("size") int size,
                                          @Param("offset") int offset);

    /** 统计关键词命中数（kw 为 null 表示全部未删除条目） */
    @Query("SELECT COUNT(*) FROM knowledge_base "
            + "WHERE is_delete = 0 "
            + "AND (:kw IS NULL OR content LIKE :kw "
            + "     OR id IN (SELECT base_id FROM knowledge_chunk "
            + "                WHERE is_delete = 0 AND keyword LIKE :kw))")
    Mono<Long> countFiltered(@Param("kw") String kw);

    /** 统计全部未删除条目数（仪表盘用） */
    @Query("SELECT COUNT(*) FROM knowledge_base WHERE is_delete = 0")
    Mono<Long> countAll();

    /** 统计全部未删除向量切块数（仪表盘用，反映已索引的文档片段量） */
    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE is_delete = 0")
    Mono<Long> countChunksAll();
}
