package xsl.sai.framework.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.framework.result.VectorRecord;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R2DBC 数据库操作客户端 —— 封装 Spring DatabaseClient，原生适配 MySQL
 *
 * <p>核心特性：
 * <ul>
 *   <li>通用查询/执行封装，参数自动绑定</li>
 *   <li>向量语义检索下推到数据库完成：借助 myvector 插件提供的 {@code myvector_distance} UDF，
 *       在 SQL 中直接计算相似度并 {@code ORDER BY ... LIMIT} 取 Top-K；
 *       向量以原生 {@code VECTOR(n)} 列存储（如 {@code embedding_vec VECTOR(384)}）</li>
 * </ul>
 *
 * @DATE: 2026/6/10 14:10
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class R2dbcClient {

    private final DatabaseClient databaseClient;

    public R2dbcClient(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");

    // ==================== 通用查询 / 执行 ====================

    /**
     * 获取一条数据
     */
    public <T> Mono<T> queryOne(String sql, Map<String, Object> params, Class<T> clazz) {
        SqlAndArgs resolved = resolveNamedParams(sql, params);
        var spec = bindAll(databaseClient.sql(resolved.sql), resolved.args);
        return spec.fetch().first()
                .map(item -> JSONUtil.toBean(JSONUtil.toJsonStr(item), clazz));
    }

    /**
     * 获取多条数据
     */
    public <T> Flux<T> queryList(String sql, Map<String, Object> params, Class<T> clazz) {
        SqlAndArgs resolved = resolveNamedParams(sql, params);
        var spec = bindAll(databaseClient.sql(resolved.sql), resolved.args);
        return spec.fetch().all()
                .map(item -> JSONUtil.toBean(JSONUtil.toJsonStr(item), clazz));
    }

    /**
     * 执行通用更新操作（新增、修改、删除）
     */
    public Mono<Long> execute(String sql, Map<String, Object> params) {
        SqlAndArgs resolved = resolveNamedParams(sql, params);
        var spec = bindAll(databaseClient.sql(resolved.sql), resolved.args);
        return spec.fetch()
                .rowsUpdated()
                .doOnSuccess(rows -> log.info("[R2dbc] 操作成功，影响行数 --> {}", rows))
                .doOnError(error -> log.error("[R2dbc] 操作失败，SQL --> {}, 参数 --> {}, 错误 --> {}",
                        resolved.sql, resolved.args, error.getMessage()));
    }

    /**
     * 执行插入并返回数据库生成的自增主键（默认取名为 {@code id} 的列）。
     * 适用于 INSERT 后需要拿到新记录主键的场景。
     */
    public Mono<Long> executeReturnId(String sql, Map<String, Object> params) {
        SqlAndArgs resolved = resolveNamedParams(sql, params);
        var spec = bindAll(databaseClient.sql(resolved.sql), resolved.args);
        return spec.filter(stmt -> stmt.returnGeneratedValues("id"))
                .fetch()
                .first()
                .map(row -> {
                    Object id = row.get("id");
                    return id instanceof Number ? ((Number) id).longValue() : 0L;
                })
                .doOnError(error -> log.error("[R2dbc] 插入取主键失败，SQL --> {}, 参数 --> {}, 错误 --> {}",
                        resolved.sql, resolved.args, error.getMessage()));
    }

    /**
     * 将命名参数 :name 转换为位置参数 ?，返回转换后的 SQL 和按位置排序的参数值列表。
     * MariaDB R2DBC 1.x 仅支持 ? 占位符，需此转换。
     */
    private SqlAndArgs resolveNamedParams(String sql, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new SqlAndArgs(sql, Collections.emptyList());
        }
        Matcher matcher = NAMED_PARAM.matcher(sql);
        List<Object> orderedArgs = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "?");
            String name = matcher.group(1);
            orderedArgs.add(params.get(name));
        }
        matcher.appendTail(sb);
        return new SqlAndArgs(sb.toString(), orderedArgs);
    }

    /** 按位置顺序绑定所有参数（null 使用 bindNull，避免 MariaDB R2DBC 拒绝 null 绑定） */
    private DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, List<Object> args) {
        for (int i = 0; i < args.size(); i++) {
            Object val = args.get(i);
            if (val == null) {
                spec = spec.bindNull(i, Object.class);
            } else {
                spec = spec.bind(i, val);
            }
        }
        return spec;
    }

    /** 内部辅助：位置 SQL + 有序参数 */
    private record SqlAndArgs(String sql, List<Object> args) {}

    // ==================== 向量语义检索（相似度计算下推到数据库，借助 myvector 插件） ====================
    // 数据库已安装 myvector 插件，提供 myvector_distance(a, b, metric) UDF，可在 SQL 内直接计算
    // 余弦 / 欧氏 / 内积相似度。检索时把查询向量以 STRING_TO_VECTOR(...) 字面量内联进 SQL，
    // 由数据库完成 打分 + HAVING 阈值过滤 + ORDER BY + LIMIT，只回传 Top-K，避免全表拉回应用层。
    // 向量以原生 VECTOR(n) 列存储（如 embedding_vec VECTOR(384)）。

    private enum VectorScoreMode { COSINE, L2, INNER_PRODUCT }

    /**
     * 向量余弦相似度搜索（值越大越相似）
     *
     * @param queryVector      查询向量 (float[])
     * @param vectorColumn 存储 embedding 的原生向量列（如 {@code "kc.embedding_vec"}，类型为 VECTOR(384)）
     * @param selectColumns    SELECT 列
     * @param fromClause       FROM/JOIN
     * @param whereFilter      额外 WHERE 条件，可为 null
     * @param whereParams      条件参数 Map
     * @param topK             返回条数
     * @param threshold        相似度阈值 [0, 1]，只返回相似度 >= 该值的结果
     * @param clazz            映射实体类
     * @param <T>              返回类型
     */
    public <T> Flux<T> vectorSearch(
            float[] queryVector,
            String vectorColumn,
            String selectColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double threshold,
            Class<T> clazz) {
        return fetchAndScore(queryVector, vectorColumn, selectColumns, fromClause, whereFilter, whereParams,
                        VectorScoreMode.COSINE, topK, threshold)
                .map(m -> JSONUtil.toBean(JSONUtil.toJsonStr(m), clazz));
    }

    /**
     * 向量 L2 距离搜索（欧氏距离，值越小越相似）
     */
    public <T> Flux<T> vectorSearchL2(
            float[] queryVector,
            String vectorColumn,
            String selectColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double maxDistance,
            Class<T> clazz) {
        return fetchAndScore(queryVector, vectorColumn, selectColumns, fromClause, whereFilter, whereParams,
                        VectorScoreMode.L2, topK, maxDistance)
                .map(m -> JSONUtil.toBean(JSONUtil.toJsonStr(m), clazz));
    }

    /**
     * 向量内积搜索（值越大越相关，适合已归一化的向量）
     */
    public <T> Flux<T> vectorSearchInnerProduct(
            float[] queryVector,
            String vectorColumn,
            String selectColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double minScore,
            Class<T> clazz) {
        return fetchAndScore(queryVector, vectorColumn, selectColumns, fromClause, whereFilter, whereParams,
                        VectorScoreMode.INNER_PRODUCT, topK, minScore)
                .map(m -> JSONUtil.toBean(JSONUtil.toJsonStr(m), clazz));
    }

    // ==================== 向量搜索（结构化返回 VectorRecord） ====================

    /**
     * 向量余弦相似度搜索 —— 返回标准 {@link VectorRecord}，自动组装 content / metadata / score
     */
    public Flux<VectorRecord> vectorSearch(
            float[] queryVector,
            String vectorColumn,
            String contentColumn,
            Map<String, String> metadataColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double threshold,
            boolean includeEmbedding) {

        String selectColumns = buildSelectColumns(contentColumn, metadataColumns,
                includeEmbedding ? vectorColumn : null);
        return vectorSearch(queryVector, vectorColumn, selectColumns, fromClause,
                whereFilter, whereParams, topK, threshold, Map.class)
                .map(row -> mapToResult(row, contentColumn, metadataColumns,
                        includeEmbedding ? vectorColumn : null, "__score"));
    }

    /**
     * 向量 L2 距离搜索 —— 返回标准 {@link VectorRecord}，score 为距离（越小越相似）
     */
    public Flux<VectorRecord> vectorSearchL2(
            float[] queryVector,
            String vectorColumn,
            String contentColumn,
            Map<String, String> metadataColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double maxDistance,
            boolean includeEmbedding) {

        String selectColumns = buildSelectColumns(contentColumn, metadataColumns,
                includeEmbedding ? vectorColumn : null);
        return vectorSearchL2(queryVector, vectorColumn, selectColumns, fromClause,
                whereFilter, whereParams, topK, maxDistance, Map.class)
                .map(row -> mapToResult(row, contentColumn, metadataColumns,
                        includeEmbedding ? vectorColumn : null, "__score"));
    }

    /**
     * 向量内积搜索 —— 返回标准 {@link VectorRecord}
     */
    public Flux<VectorRecord> vectorSearchInnerProduct(
            float[] queryVector,
            String vectorColumn,
            String contentColumn,
            Map<String, String> metadataColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            int topK,
            double minScore,
            boolean includeEmbedding) {

        String selectColumns = buildSelectColumns(contentColumn, metadataColumns,
                includeEmbedding ? vectorColumn : null);
        return vectorSearchInnerProduct(queryVector, vectorColumn, selectColumns, fromClause,
                whereFilter, whereParams, topK, minScore, Map.class)
                .map(row -> mapToResult(row, contentColumn, metadataColumns,
                        includeEmbedding ? vectorColumn : null, "__score"));
    }

    // ==================== 内部辅助 ====================

    /**
     * 构建向量检索 SQL：将相似度计算下推到数据库（myvector 插件的 myvector_distance UDF）。
     * 查询向量以 STRING_TO_VECTOR(...) 字面量内联（仅含数字，安全），
     * 由数据库完成 打分 → HAVING 阈值过滤 → ORDER BY → LIMIT，只回传 Top-K。
     *
     * <p>各模式映射：
     * <ul>
     *   <li>COSINE：score = 1 - distance，越大越相似，HAVING score &gt;= threshold</li>
     *   <li>L2：score = sqrt(distance)，越小越相似，HAVING score &lt;= threshold</li>
     *   <li>INNER_PRODUCT：myvector_distance(...,'IP')，越大越相关，HAVING score &gt;= threshold</li>
     * </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flux<Map<String, Object>> fetchAndScore(
            float[] queryVector,
            String vectorColumn,
            String selectColumns,
            String fromClause,
            String whereFilter,
            Map<String, Object> whereParams,
            VectorScoreMode mode,
            int topK,
            double threshold) {
        String vectorLiteral = "STRING_TO_VECTOR('" + toVectorJson(queryVector) + "')";
        String scoreExpr;
        String orderDir;
        String havingOp;
        switch (mode) {
            case COSINE -> {
                scoreExpr = "(1 - myvector_distance(" + vectorLiteral + ", " + vectorColumn + ", 'COSINE'))";
                orderDir = "DESC";
                havingOp = ">= " + formatDouble(threshold);
            }
            case L2 -> {
                scoreExpr = "SQRT(myvector_distance(" + vectorLiteral + ", " + vectorColumn + ", 'L2'))";
                orderDir = "ASC";
                havingOp = "<= " + formatDouble(threshold);
            }
            default -> { // INNER_PRODUCT
                scoreExpr = "myvector_distance(" + vectorLiteral + ", " + vectorColumn + ", 'IP')";
                orderDir = "DESC";
                havingOp = ">= " + formatDouble(threshold);
            }
        }
        String sql = "SELECT " + selectColumns + ", " + scoreExpr + " AS __score FROM " + fromClause
                + " WHERE 1=1"
                + (StrUtil.isNotBlank(whereFilter) ? " AND " + whereFilter : "")
                + " HAVING __score " + havingOp
                + " ORDER BY __score " + orderDir
                + " LIMIT " + topK;
        Map<String, Object> params = whereParams == null ? Map.of() : whereParams;
        log.debug("[R2dbc] 向量检索SQL(mode --> {}) --> {}", mode, sql);
        return queryList(sql, params, Map.class)
                .map(row -> (Map<String, Object>) row);
    }

    /**
     * 将 float[] 格式化为向量 JSON 字面量（如 {@code [0.1,0.2,0.3]}），仅含数字，可安全内联进 SQL
     */
    private String toVectorJson(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT, "%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private String formatDouble(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /**
     * 构建 SELECT 列字符串：content 列 + metadata 列 + 可选 embedding 列
     */
    private String buildSelectColumns(String contentColumn,
                                       Map<String, String> metadataColumns,
                                       String embeddingColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append(contentColumn).append(" AS __content");
        if (metadataColumns != null) {
            for (var entry : metadataColumns.entrySet()) {
                sb.append(", ").append(entry.getValue()).append(" AS __meta_").append(entry.getKey());
            }
        }
        if (embeddingColumn != null) {
            sb.append(", ").append(embeddingColumn).append(" AS __embedding");
        }
        return sb.toString();
    }

    /**
     * 将原始行 Map 转换为 {@link VectorRecord}
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private VectorRecord mapToResult(Map row,
                                            String contentColumn,
                                            Map<String, String> metadataColumns,
                                            String embeddingColumn,
                                            String scoreAlias) {
        VectorRecord r = new VectorRecord();

        // content
        Object content = row.get("__content");
        r.setContent(content != null ? content.toString() : null);

        // metadata
        if (metadataColumns != null) {
            for (String key : metadataColumns.keySet()) {
                Object val = row.get("__meta_" + key);
                if (val != null) {
                    r.getMetadata().put(key, val);
                }
            }
        }

        // embedding
        if (embeddingColumn != null) {
            Object emb = row.get("__embedding");
            if (emb instanceof float[] fa) {
                r.setEmbedding(fa);
            } else if (emb instanceof List list) {
                float[] arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    arr[i] = item instanceof Number ? ((Number) item).floatValue() : Float.parseFloat(item.toString());
                }
                r.setEmbedding(arr);
            }
        }

        // score
        Object scoreObj = row.get(scoreAlias);
        if (scoreObj instanceof Number num) {
            r.setScore(num.doubleValue());
        }

        return r;
    }

}
