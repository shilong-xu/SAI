package xsl.sai.client.service.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.client.domain.KnowledgeBaseEntity;
import xsl.sai.client.mapper.KnowledgeBaseMapper;
import xsl.sai.client.pojo.dto.KnowledgeBaseDTO;
import xsl.sai.client.pojo.vo.KnowledgeBaseVO;
import xsl.sai.client.service.KnowledgeBaseService;
import xsl.sai.agent.handler.SemanticRefineHandler;
import xsl.sai.framework.client.EmbeddingClient;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.framework.result.VectorRecord;
import xsl.sai.framework.util.VectorUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 知识库 RAG 服务实现
 *
 * <p>设计：knowledge_chunk 不再存储「切块文本」，而是存储由 knowledge_base 原文提炼出的<b>关键词</b>，
 * 每个关键词单独向量化（bge-m3, 1024维），作为知识库语义检索的入口。检索时由 knowledge_chunk 命中关键词，
 * 再回填对应的 knowledge_base 条目正文返回。
 *
 * <p>新增/修改时：
 * <ol>
 *   <li>优先用 {@link SemanticRefineHandler#extractKeywords} 对「文本内容」提炼关键词；
 *       超长文本（>{@code SEGMENT_MIN_CHARS}）先经 {@link SemanticRefineHandler#segment} 语义分块，逐块提炼关键词后合并去重，避免模型上下文溢出；</li>
 *   <li>对每个关键词单独调用 {@link EmbeddingClient} 向量化，写入 knowledge_chunk（base_id, keyword, embedding_vec）；</li>
 *   <li>关键词全部去重；若关键词为空但向量可用，用截断后的正文作为单个关键词兜底，保证条目可被检索；</li>
 *   <li>向量化服务不可用时，关键词仍入库但向量列留空（保证关键词数据不丢，仅暂不可语义检索）。</li>
 * </ol>
 *
 * @author SAI
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /** 默认切块大小（token 数） */
    private static final int DEFAULT_CHUNK_SIZE = 200;

    /** 超长文本阈值：超过该字符数先语义分块再逐块提炼关键词（与 SemanticRefineHandler.SEGMENT_MIN_CHARS 对齐，避免模型上下文溢出） */
    private static final int SEGMENT_MIN_CHARS = 1000;

    /**
     * 语义检索默认相似度阈值（COSINE，越大越相关）。
     * 关键词索引下查询句是自然语言长句、被召回的是短关键词，余弦分天然偏低
     * （实测最相关命中约 0.73），沿用 0.8 会过滤掉几乎所有结果，故默认取 0.6。
     */
    private static final double DEFAULT_THRESHOLD = 0.6;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final R2dbcClient r2dbcClient;
    private final EmbeddingClient embeddingClient;
    /** 语义提炼 Handler：知识库切块写入前先提炼为精简语义，再向量化存储 */
    private final SemanticRefineHandler semanticRefineHandler;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    R2dbcClient r2dbcClient,
                                    EmbeddingClient embeddingClient,
                                    SemanticRefineHandler semanticRefineHandler) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.r2dbcClient = r2dbcClient;
        this.embeddingClient = embeddingClient;
        this.semanticRefineHandler = semanticRefineHandler;
    }

    @Override
    @Transactional
    public Mono<KnowledgeBaseVO> save(KnowledgeBaseDTO dto) {
        int chunkSize = DEFAULT_CHUNK_SIZE;
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        BeanUtils.copyProperties(dto, entity);
        if (entity.getContent() == null) entity.setContent("");
        LocalDateTime now = LocalDateTime.now();

        Mono<KnowledgeBaseEntity> saved;
        if (dto.getId() == null) {
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            saved = knowledgeBaseMapper.save(entity);
        } else {
            saved = knowledgeBaseMapper.findActiveById(dto.getId())
                    .flatMap(existing -> {
                        existing.setContent(entity.getContent());
                        existing.setRemark(entity.getRemark());
                        existing.setUpdateTime(now);
                        return knowledgeBaseMapper.save(existing);
                    })
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("知识库条目不存在或已删除: " + dto.getId())));
        }

        // null=自动提炼；非 null=以手动关键词为准（空数组表示用户清空，不再兜底）
        List<String> manualKeywords = dto.getKeywords();

        return saved.flatMap(base -> {
            // 先逻辑删除旧切块，再写入新切块（更新场景下重建向量）
            Map<String, Object> delParams = Collections.singletonMap("baseId", base.getId());
            return r2dbcClient.execute(
                            "UPDATE knowledge_chunk SET is_delete=1 "
                                    + "WHERE base_id=:baseId AND is_delete=0", delParams)
                    .timeout(Duration.ofSeconds(20))
                    .then(writeChunks(base.getId(), base.getContent(), chunkSize, manualKeywords))
                    .thenReturn(base);
        }).map(this::toVO);
    }

    /**
     * 只提炼关键词、不落库 —— 供前端编辑弹窗「自动生成关键词」预览用。
     *
     * @param content   原文
     */
    @Override
    public Mono<List<String>> extractKeywordsOnly(String content) {
        if (content == null || content.isBlank()) {
            return Mono.just(List.of());
        }
        return extractKeywords(content, DEFAULT_CHUNK_SIZE)
                .map(KnowledgeBaseServiceImpl::dedupNonBlank)
                .defaultIfEmpty(List.of());
    }

    /**
     * 把文本内容提炼为关键词集合（超长文本先语义分块、逐块提炼后合并去重），
     * 再<b>批量向量化</b>（一次 HTTP，而非每个关键词一次）后一次性写入 knowledge_chunk（base_id, keyword, embedding_vec）。
     * 关键词全部去重；若关键词为空但向量可用，用截断后的正文作为单个关键词兜底，保证条目可被检索。
     *
     * <p><b>注意</b>：{@link EmbeddingClient#embed} 内部是同步阻塞 HTTP，必须切到 {@code boundedElastic}，
     * 否则会阻塞 Reactor Netty 事件循环线程。
     */
    private Mono<Void> writeChunks(Long baseId, String content, int chunkSize) {
        return writeChunks(baseId, content, chunkSize, null);
    }

    /**
     * @param manualKeywords 手动关键词；<b>null 表示自动提炼</b>，非 null 则完全以它为准（不再调模型、也不再兜底）
     */
    private Mono<Void> writeChunks(Long baseId, String content, int chunkSize, List<String> manualKeywords) {
        boolean manual = manualKeywords != null;
        Mono<List<String>> kwMono = manual ? Mono.just(manualKeywords) : extractKeywords(content, chunkSize);
        return kwMono
                .flatMap(keywords -> {
                    boolean embeddingOn = embeddingClient != null && embeddingClient.isEnabled();
                    // 兜底：自动提炼为空时，用截断正文作为单个关键词，保证该条目仍可被语义检索。
                    // 手动指定（manual）时不兜底 —— 用户清空就是明确不要，静默塞回正文会覆盖其意图。
                    List<String> raw = (!manual && (keywords == null || keywords.isEmpty()))
                            ? (embeddingOn && content != null && !content.isBlank()
                                ? List.of(SemanticRefineHandler.safeClamp(content)) : List.of())
                            : keywords;
                    List<String> kws = dedupNonBlank(raw);
                    if (kws.isEmpty()) {
                        return Mono.empty();
                    }
                    if (!embeddingOn) {
                        return insertKeywords(baseId, kws, null);
                    }
                    // 一次批量向量化（Ollama /api/embed），避免 N 个关键词发 N 次 HTTP
                    return Mono.fromCallable(() -> embeddingClient.embed(kws))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(vecs -> insertKeywords(baseId, kws, toVecJsons(kws, vecs)))
                            .onErrorResume(e -> {
                                // 向量化失败也要把关键词落库（向量留空），保证数据不丢、后续可重建索引
                                log.warn("[Knowledge] 关键词批量向量化失败，改为写入无向量关键词: {}", e.getMessage());
                                return insertKeywords(baseId, kws, null);
                            });
                });
    }

    /** 去空 + 去 trim 后去重，保持顺序 */
    private static List<String> dedupNonBlank(List<String> src) {
        if (src == null || src.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String s : src) {
            if (s == null) {
                continue;
            }
            String kw = s.trim();
            if (!kw.isEmpty()) {
                seen.add(kw);
            }
        }
        return new ArrayList<>(seen);
    }

    /** 把批量向量化结果转成与关键词一一对应的向量 JSON 列表（缺失/异常位置为 null） */
    private static List<String> toVecJsons(List<String> kws, List<float[]> vecs) {
        List<String> out = new ArrayList<>(kws.size());
        for (int i = 0; i < kws.size(); i++) {
            float[] v = (vecs != null && i < vecs.size()) ? vecs.get(i) : null;
            out.add(v != null && v.length > 0 ? VectorUtils.toVecJson(v) : null);
        }
        return out;
    }

    /**
     * 提炼关键词：短文本直接提炼；超长文本（>{@code SEGMENT_MIN_CHARS}）先语义分块，
     * 逐块提炼后合并去重，避免一次性把超长原文喂给提炼模型导致上下文溢出。
     */
    private Mono<List<String>> extractKeywords(String content, int chunkSize) {
        if (content == null || content.isBlank()) {
            return Mono.just(List.of());
        }
        final int cs = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        if (content.length() <= SEGMENT_MIN_CHARS) {
            return semanticRefineHandler.extractKeywords(content, 0);
        }
        return semanticRefineHandler.segment(content, cs)
                .flatMapMany(chunks -> chunks.isEmpty() ? Flux.empty() : Flux.fromIterable(chunks))
                .flatMap(chunk -> semanticRefineHandler.extractKeywords(chunk, 0))
                .reduce(new LinkedHashSet<String>(), (set, list) -> {
                    if (list != null) {
                        for (String k : list) {
                            if (k != null) {
                                set.add(k.trim());
                            }
                        }
                    }
                    return set;
                })
                .map(LinkedHashSet::new)
                .map(set -> (List<String>) new ArrayList<>(set))
                .defaultIfEmpty(new ArrayList<>());
    }

    /**
     * 批量写入 knowledge_chunk：一条多值 INSERT 落全部关键词（每个关键词一行、各带自己的向量）。
     * {@code vecJsons} 与 {@code keywords} 一一对应，元素为 null 表示该关键词无向量（embedding_vec 留 NULL）。
     */
    private Mono<Void> insertKeywords(Long baseId, List<String> keywords, List<String> vecJsons) {
        if (keywords == null || keywords.isEmpty()) {
            return Mono.empty();
        }
        StringBuilder sql = new StringBuilder("INSERT INTO knowledge_chunk (base_id, keyword, embedding_vec, is_delete) VALUES ");
        Map<String, Object> p = new LinkedHashMap<>(keywords.size() * 2 + 1);
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            p.put("kw" + i, keywords.get(i));
            p.put("baseId" + i, baseId);
            String vj = (vecJsons != null && i < vecJsons.size()) ? vecJsons.get(i) : null;
            if (vj != null) {
                p.put("vec" + i, vj);
                sql.append("(:baseId").append(i).append(", :kw").append(i)
                        .append(", STRING_TO_VECTOR(:vec").append(i).append("), 0)");
            } else {
                sql.append("(:baseId").append(i).append(", :kw").append(i).append(", NULL, 0)");
            }
        }
        return r2dbcClient.execute(sql.toString(), p)
                .timeout(Duration.ofSeconds(30))
                .then();
    }

    @Override
    public Mono<Map<String, Object>> page(int pageNum, int pageSize, String keyword) {
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        int offset = (page - 1) * size;
        // 关键词模糊搜索：命中「正文」或「已提炼的关键词」都算命中（关键词是向量索引入口，正文未必逐字出现）。
        // 空值统一归一为 null → Mapper 里的 (:kw IS NULL OR ...) 走不过滤分支，无搜索态复用同一条 SQL。
        final String kw = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";
        return knowledgeBaseMapper.countFiltered(kw)
                .defaultIfEmpty(0L)
                .flatMap(total -> knowledgeBaseMapper.pageFiltered(kw, size, offset)
                        .map(this::toVO)
                        .collectList()
                        // 一次批量查回本页所有条目的关键词，避免 N+1
                        .flatMap(list -> fillKeywords(list).thenReturn(list))
                        .map(list -> {
                            Map<String, Object> m = new LinkedHashMap<>(2);
                            m.put("list", list);
                            m.put("total", total);
                            return m;
                        }));
    }

    /**
     * 批量回填关键词：按 base_id 一次性查 knowledge_chunk，再分组写入 VO。
     *
     * <p>SQL 中把 base_id 显式别名为驼峰 baseId —— R2dbcClient 走 JSON 反序列化，
     * 下划线列名无法自动映射到驼峰字段。
     */
    private Mono<Void> fillKeywords(List<KnowledgeBaseVO> list) {
        List<Long> ids = new ArrayList<>(list.size());
        for (KnowledgeBaseVO vo : list) {
            if (vo.getId() != null) {
                ids.add(vo.getId());
            }
        }
        if (ids.isEmpty()) {
            return Mono.empty().then();
        }
        Map<String, Object> p = new LinkedHashMap<>(ids.size() + 1);
        StringBuilder sql = new StringBuilder(
                "SELECT base_id AS baseId, keyword FROM knowledge_chunk WHERE is_delete=0 AND base_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            String k = "bid" + i;
            sql.append(':').append(k);
            p.put(k, ids.get(i));
        }
        sql.append(") ORDER BY base_id, id");
        return r2dbcClient.queryList(sql.toString(), p, KeywordRow.class)
                .collectList()
                .map(rows -> {
                    Map<Long, List<String>> grouped = new LinkedHashMap<>();
                    for (KeywordRow r : rows) {
                        if (r.getBaseId() == null || r.getKeyword() == null) {
                            continue;
                        }
                        grouped.computeIfAbsent(r.getBaseId(), k -> new ArrayList<>()).add(r.getKeyword());
                    }
                    return grouped;
                })
                .doOnNext(grouped -> list.forEach(vo ->
                        vo.setKeywords(grouped.getOrDefault(vo.getId(), Collections.emptyList()))))
                .timeout(Duration.ofSeconds(20))
                .onErrorResume(e -> {
                    log.warn("[KB] 批量回填关键词失败（不影响列表返回）: {}", e.getMessage());
                    return Mono.just(Collections.emptyMap());
                })
                .then();
    }

    /** 关键词行投影（配合 {@link #fillKeywords} 使用） */
    @Getter
    @Setter
    public static class KeywordRow {
        private Long baseId;
        private String keyword;
    }

    @Override
    public Mono<KnowledgeBaseVO> detail(Long id) {
        // 详情同样回填关键词（列表页已有该字段，保持一致，供详情弹窗展示）
        return knowledgeBaseMapper.findActiveById(id)
                .map(this::toVO)
                .flatMap(vo -> fillKeywords(Collections.singletonList(vo)).thenReturn(vo));
    }

    @Override
    @Transactional
    public Mono<Void> remove(Long id) {
        // ① knowledge_base 软删除：优先使用 Mapper（ReactiveCrudRepository.save()）
        return knowledgeBaseMapper.findActiveById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("知识库条目不存在或已删除: " + id)))
                .flatMap(entity -> {
                    entity.setIsDelete(true);
                    entity.setUpdateTime(LocalDateTime.now());
                    return knowledgeBaseMapper.save(entity);
                })
                // ② knowledge_chunk 批量软删除：无 Mapper，只能用 R2DBC
                .then(r2dbcClient.execute(
                        "UPDATE knowledge_chunk SET is_delete=1 WHERE base_id=:id AND is_delete=0",
                        Collections.singletonMap("id", id))
                        .timeout(Duration.ofSeconds(20)))
                .then();
    }

    @Override
    public Flux<KnowledgeBaseVO> semanticSearch(String text, int topK, Double threshold) {
        if (text == null || text.trim().isEmpty()) {
            return Flux.empty();
        }
        // 阈值由前端传入(0~1)；未传时用 DEFAULT_THRESHOLD（0.6，关键词索引下的合理值）
        double th = (threshold == null) ? DEFAULT_THRESHOLD : threshold;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("base_id", "kc.base_id");
        // 关键词检索：用 knowledge_chunk.keyword 的向量召回，再回填对应 knowledge_base 正文
        return Mono.fromCallable(() -> embeddingClient.embed(text))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(qv -> r2dbcClient.vectorSearch(
                        qv, "kc.embedding_vec", "kc.keyword", meta,
                        "knowledge_chunk kc", "kc.is_delete = 0",
                        Collections.emptyMap(), topK <= 0 ? 5 : topK, th, false))
                // 必须用 flatMapSequential：SQL 已按 __score DESC 排序，并发 flatMap 会打乱顺序，
                // 导致后面的 distinct 保留的不是同一条目下相似度最高的那条。
                .flatMapSequential(this::enrichChunk)
                // 同一 knowledge_base 可能被多个关键词命中，按 base_id 去重（保留相似度最高的首条）
                .distinct(vo -> vo.getBaseId() == null ? new Object() : vo.getBaseId());
    }

    /** 把关键词命中转 VO：记录命中的关键词与相似度，并异步回填所属 knowledge_base 条目正文（保持响应式，不阻塞） */
    private Mono<KnowledgeBaseVO> enrichChunk(VectorRecord r) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setKeyword(r.getContent());
        vo.setScore(r.getScore());
        Map<String, Object> md = r.getMetadata();
        if (md == null) {
            return Mono.just(vo);
        }
        Long baseId = VectorUtils.toLong(md.get("base_id"));
        vo.setBaseId(baseId);
        if (baseId == null) {
            return Mono.just(vo);
        }
        return knowledgeBaseMapper.findActiveById(baseId)
                .map(e -> {
                    vo.setId(e.getId());
                    vo.setContent(e.getContent());
                    vo.setRemark(e.getRemark());
                    vo.setCreateTime(e.getCreateTime());
                    vo.setUpdateTime(e.getUpdateTime());
                    return vo;
                })
                .defaultIfEmpty(vo);
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseEntity e) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        BeanUtils.copyProperties(e, vo);
        return vo;
    }
}
