package xsl.sai.agent.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.EmbeddingClient;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.framework.result.VectorRecord;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据库查询工具（声明式 {@link Tool}）—— 基于 {@link R2dbcClient}。
 *
 * <p>提供三类能力：
 * <ul>
 *   <li><b>execute_sql</b>：执行自定义<b>只读</b> SQL 查询业务数据；内置安全闸门，
 *       拒绝 INSERT/UPDATE/DELETE/DROP 等写删改操作，避免误伤数据；
 *       对向量列（embedding_json 等 JSON 文本）做友好摘要，避免大模型被超长向量刷屏。</li>
 *   <li><b>vector_search</b>：向量语义检索。数据库使用 MySQL（无 pgvector），
 *       相似度在应用层由 {@link R2dbcClient} 计算（余弦）。
 *       支持直接传入「查询向量」或传入「查询文本」由 {@link EmbeddingClient}
 *       （本地 embeddings 服务，默认端口 11434，Ollama bge-m3）自动向量化后再检索。</li>
 *   <li><b>execute_write</b>：<b>受控</b>数据写/改工具，仅放行 INSERT / UPDATE 两类 DML。
 *       内置多重安全闸门（禁 DELETE、禁 DDL/DCL、单语句、UPDATE 必须带 WHERE、受保护系统表禁止写入），
 *       且采用<b>显式确认门控</b>（{@code confirm=true} 才真正执行，并提供审计 reason），
 *       避免大模型静默篡改数据。</li>
 * </ul>
 *
 * <p><b>纯流式、零阻塞</b>：方法返回 {@link Mono<String>}，底层 R2DBC 响应式驱动，
 * 向量化（同步 HTTP）以 {@code Mono.fromCallable} 包装在独立调度线程执行，不再使用 {@code .block()}。
 *
 * @DATE: 2026/7/20
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class SqlTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final R2dbcClient r2dbcClient;
    private final EmbeddingClient embeddingClient;

    public SqlTool(R2dbcClient r2dbcClient, EmbeddingClient embeddingClient) {
        this.r2dbcClient = r2dbcClient;
        this.embeddingClient = embeddingClient;
    }

    // ==================== 1. 自定义只读 SQL 查询 ====================

    @Tool(name = "execute_sql",
            description = "执行自定义只读 SQL 查询数据库数据。"
                    + "仅允许 SELECT / WITH / SHOW / DESCRIBE / EXPLAIN 等读操作；"
                    + "禁止 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE 等写/删/改操作，以保障数据安全。"
                    + "适用于：查询业务表、统计汇总、查看表结构等。"
                    + "结果以 JSON 返回，向量列（如 embedding_json）会自动做友好摘要。"
                    + "如要做向量语义检索，请改用 vector_search 工具。")
    @SuppressWarnings("unchecked")
    public Mono<String> executeSql(
            RuntimeContext runtimeContext,
            @ToolParam(name = "sql", required = true,
                    description = "要执行的只读 SQL，例如 SELECT * FROM t LIMIT 10") String sql,
            @ToolParam(name = "max_rows", required = false,
                    description = "返回最大行数，默认 50，最大 200") Integer maxRows) {
        try {
            // ---- 安全闸门：仅放行只读语句 ----
            String check = sql == null ? "" : sql.trim()
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("--.*", " ")
                    .toUpperCase(Locale.ROOT);
            if (!check.matches("^(SELECT|WITH|SHOW|DESCRIBE|DESC|EXPLAIN)\\b.*")) {
                return Mono.just("⛔ 安全限制：execute_sql 仅允许只读查询（SELECT/WITH/SHOW/DESCRIBE/EXPLAIN）。\n"
                        + "检测到写/删/改类语句，已拒绝执行，以保障数据安全。\n原 SQL:\n" + sql);
            }

            int limit = (maxRows == null || maxRows <= 0) ? 50 : Math.min(maxRows, 200);
            return r2dbcClient
                    .queryList(sql, Collections.emptyMap(), Map.class)
                    .collectList()
                    .timeout(TIMEOUT)
                    .map(raw -> {
                        List<Map<String, Object>> rows = (raw == null) ? List.of()
                                : raw.stream().map(m -> (Map<String, Object>) m).toList();
                        if (rows.isEmpty()) {
                            return "查询成功，无匹配数据。";
                        }
                        int total = rows.size();
                        List<Map<String, Object>> view = rows.stream()
                                .limit(limit)
                                .map(SqlTool::friendlyRow)
                                .collect(Collectors.toList());
                        String json = JSONUtil.toJsonPrettyStr(view);

                        StringBuilder sb = new StringBuilder();
                        sb.append("查询成功，返回 ").append(total).append(" 行（预览前 ")
                                .append(view.size()).append(" 行）：\n").append(json);
                        if (total > view.size()) {
                            sb.append("\n...[已截断，共 ").append(total).append(" 行，请用 LIMIT 缩小范围]");
                        }
                        return sb.toString();
                    })
                    .onErrorResume(e -> {
                        log.warn("execute_sql 失败: {}", e.getMessage());
                        return Mono.just("SQL 执行失败：" + e.getMessage());
                    });
        } catch (Exception e) {
            log.warn("execute_sql 失败: {}", e.getMessage());
            return Mono.just("SQL 执行失败：" + e.getMessage());
        }
    }

    // ==================== 2. 向量语义检索 ====================

    @Tool(name = "vector_search",
            description = "向量语义检索（相似度搜索）。适用于知识库 / embedding 表的语义查询。"
                    + "本工程数据库为 MySQL（已安装 myvector 向量插件），相似度由 myvector_distance 在 SQL 内直接计算（余弦），向量以原生 VECTOR 列存储。"
                    + "支持两种查询输入（二选一，优先 query_text）："
                    + "① query_text：提供自然语言文本，将自动调用本地 embeddings 服务(Ollama 11434, bge-m3)生成向量后检索；"
                    + "② query_vector_json：直接提供 float 数组向量 JSON。"
                    + "返回按相似度降序的 Top-K（content + metadata + score）。")
    public Mono<String> vectorSearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query_text", required = false,
                    description = "查询文本（自然语言）。提供后自动调用本地 embeddings 服务生成向量，"
                            + "与 query_vector_json 二选一，优先使用。例如「如何使用向量检索」") String queryText,
            @ToolParam(name = "query_vector_json", required = false,
                    description = "查询向量 float 数组 JSON，例如 [0.1,0.2,0.3,...]。"
                            + "仅当未提供 query_text 时使用。") String queryVectorJson,
            @ToolParam(name = "from_clause", required = true,
                    description = "FROM 子句，可含 JOIN，例如 knowledge_chunk kc") String fromClause,
            @ToolParam(name = "content_column", required = true,
                    description = "文本/内容列，例如 kc.keyword（知识库关键词表）") String contentColumn,
            @ToolParam(name = "vector_column", required = true,
                    description = "向量列（原生 VECTOR 类型），例如 kc.embedding_vec（建议 VECTOR(384)）") String vectorColumn,
            @ToolParam(name = "top_k", required = false,
                    description = "返回条数，默认 5，最大 50") Integer topK,
            @ToolParam(name = "threshold", required = true,
                    description = "余弦相似度阈值 0~1，必填，低于此值过滤（例如 0.8）") Double threshold,
            @ToolParam(name = "where_filter", required = false,
                    description = "额外 WHERE 条件（不含 AND/WHERE，可空），例如 kc.source = 'doc'") String whereFilter,
            @ToolParam(name = "metadata_columns_json", required = false,
                    description = "需返回的元数据列 JSON 对象，例如 {\"id\":\"kc.id\",\"title\":\"kc.title\"}") String metadataColumnsJson) {
        final Mono<float[]> qvMono;
        if (queryText != null && !queryText.isBlank()) {
            // 优先：文本 → 本地 embeddings 服务向量化
            if (!embeddingClient.isEnabled()) {
                return Mono.just("⚠️ 已提供 query_text，但 Embedding 客户端未启用"
                        + "（agentScope.embedding.enabled=false）。\n请先在配置中启用本地 embeddings 服务，"
                        + "或改用 query_vector_json 直接提供向量。");
            }
            qvMono = Mono.fromCallable(() -> embeddingClient.embed(queryText))
                    .flatMap(v -> (v != null && v.length > 0)
                            ? Mono.just(v)
                            : Mono.error(new IllegalStateException(
                            "Embedding 生成结果为空，请检查本地 embeddings 服务（Ollama 11434）是否可用，且已 `ollama pull bge-m3`。")))
                    .onErrorResume(e -> Mono.error(new RuntimeException("Embedding 生成失败：" + e.getMessage())));
        } else {
            // 回退：直接解析调用方提供的向量
            float[] parsed = parseVector(queryVectorJson);
            if (parsed == null) {
                return Mono.just("请提供 query_text（自动向量化）或 query_vector_json（手动向量），二者至少其一。");
            }
            qvMono = Mono.just(parsed);
        }

        int k = (topK == null || topK <= 0) ? 5 : Math.min(topK, 50);
        // 阈值由调用方传入(0~1)，兜底仅防 NPE
        double th = (threshold == null) ? 0.8 : threshold;
        Map<String, String> meta = parseMeta(metadataColumnsJson);

        return qvMono.flatMap(qv ->
                        r2dbcClient
                                .vectorSearch(qv, vectorColumn, contentColumn, meta, fromClause,
                                        whereFilter, Collections.emptyMap(), k, th, false)
                                .collectList()
                                .timeout(TIMEOUT)
                                .map(list -> {
                                    if (list == null || list.isEmpty()) {
                                        return "向量检索完成，无满足阈值（" + th + "）的结果。";
                                    }
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("向量检索返回 ").append(list.size()).append(" 条（阈值 ").append(th).append("）：\n");
                                    for (int i = 0; i < list.size(); i++) {
                                        VectorRecord r = list.get(i);
                                        sb.append(String.format("\n[%d] score=%.4f", i + 1, r.getScore()));
                                        if (r.getMetadata() != null && !r.getMetadata().isEmpty()) {
                                            sb.append(" meta=").append(r.getMetadata());
                                        }
                                        sb.append("\n  ").append(r.getContent() == null ? "" : r.getContent());
                                    }
                                    return sb.toString();
                                }))
                .onErrorResume(e -> {
                    log.warn("vector_search 失败: {}", e.getMessage());
                    return Mono.just("向量检索失败：" + e.getMessage());
                });
    }

    // ==================== 3. 受控写 / 改工具（需显式确认） ====================

    /**
     * 受保护的系统/运行时核心表：受控写工具禁止写入，需走运维/迁移流程。
     * 业务表（如 *_record、knowledge_*、received_email）不在其列，可经本工具受控写入。
     */
    private static final Set<String> PROTECTED_TABLES = Set.of(
            "schedule_task", "schedule_log",
            "agent_memory",
            "client_conversation", "client_message",
            "notify_message");

    @Tool(name = "execute_write",
            description = "受控的数据写/改工具，仅执行 INSERT / UPDATE 两类 DML，用于业务数据的写入与更新。"
                    + "安全闸门：① 禁止 DELETE（请改用 UPDATE ... SET is_delete = 1 软删除，符合本工程规范）；"
                    + "② 禁止 DDL/DCL（CREATE/ALTER/DROP/TRUNCATE/RENAME/GRANT/REVOKE/MERGE）；"
                    + "③ 禁止多语句批量；④ UPDATE 必须带 WHERE 条件，避免全表更新；"
                    + "⑤ 受保护系统表（schedule_task/schedule_log/agent_memory/client_conversation/client_message/notify_message）禁止写入，需走运维流程。"
                    + "本工具采用**显式确认门控**：必须 confirm=true 才会真正执行，否则仅返回操作预览并拒绝执行；"
                    + "执行前需提供 reason（变更原因，记入审计日志）。"
                    + "INSERT 返回自增主键 id，UPDATE 返回影响行数。")
    public Mono<String> executeWrite(
            RuntimeContext runtimeContext,
            @ToolParam(name = "sql", required = true,
                    description = "受控写 SQL，仅 INSERT 或 UPDATE。例如 INSERT INTO knowledge_base(...) VALUES(...) 或 UPDATE knowledge_base SET is_delete=1 WHERE id=?") String sql,
            @ToolParam(name = "confirm", required = true,
                    description = "是否确认执行。false（默认）仅预览不执行；必须为 true 才会真正写入数据。") boolean confirm,
            @ToolParam(name = "reason", required = false,
                    description = "变更原因/审计说明（confirm=true 时建议必填），将记入日志。") String reason) {
        try {
            // 1) 清洗注释 + 多语句检测
            String stmt = sql.trim()
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("--[^\\n]*", " ")
                    .replaceAll("#[^\\n]*", " ")
                    .trim()
                    .replaceAll(";\\s*$", "")
                    .trim();
            if (stmt.matches(".*;.*")) {
                return Mono.just(refuse("检测到多条 SQL 语句（含 ';' 分隔的批量）。受控写工具仅支持单条 INSERT/UPDATE，已拒绝。"));
            }
            String upper = stmt.toUpperCase(Locale.ROOT);

            // 2) 仅放行 INSERT / UPDATE
            boolean isInsert = upper.startsWith("INSERT");
            boolean isUpdate = upper.startsWith("UPDATE");
            if (!isInsert && !isUpdate) {
                return Mono.just(refuse("受控写工具仅允许 INSERT / UPDATE 两类 DML。已拦截：" + firstWord(upper)
                        + "。注意：DELETE 被禁止（请改用 UPDATE ... SET is_delete = 1 软删除）。"));
            }

            // 3) 拒绝 DDL/DCL 与 DELETE 关键字
            if (upper.matches(".*\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME|GRANT|REVOKE|DELETE|MERGE)\\b.*")) {
                return Mono.just(refuse("SQL 中包含被禁止的关键字（DDL/DCL/DELETE/MERGE），受控写工具禁止执行结构变更与硬删除。"));
            }

            // 4) 解析目标表 + 受保护表拦截
            String table = extractTable(stmt);
            if (table == null) {
                return Mono.just(refuse("无法解析目标表名，请检查 SQL 写法（INSERT INTO 表 / UPDATE 表）。"));
            }
            if (PROTECTED_TABLES.contains(table.toLowerCase(Locale.ROOT))) {
                return Mono.just(refuse("目标表 `" + table + "` 属于受保护系统表，受控写工具禁止写入。"
                        + "如需变更请走运维/迁移流程，或由人工在数据库客户端操作。"));
            }

            // 5) UPDATE 必须带 WHERE
            if (isUpdate && !upper.contains(" WHERE ")) {
                return Mono.just(refuse("UPDATE 缺少 WHERE 条件，将触发全表更新，已拒绝。请补充 WHERE 子句。"));
            }

            // 6) 确认门控（预览模式）
            if (!confirm) {
                String op = isInsert ? "INSERT（写入）" : "UPDATE（更新）";
                return Mono.just("⚠️ 受控写工具已启用确认门控。本次为 **预览模式**，未执行任何写入。\n"
                        + "操作类型：" + op + "\n目标表：" + table + "\n"
                        + (isUpdate ? "含 WHERE 条件：是\n" : "")
                        + "SQL 预览：\n" + stmt + "\n\n"
                        + "如确认执行，请再次调用 execute_write，并设置 confirm=true 且提供 reason（变更原因）。");
            }

            // 7) 执行（带审计 reason）
            final String execSql = stmt;
            String audit = (reason == null || reason.isBlank()) ? "(未提供 reason)" : reason;
            if (isInsert) {
                return r2dbcClient.executeReturnId(execSql, Collections.emptyMap())
                        .timeout(TIMEOUT)
                        .map(id -> {
                            log.info("[受控写] INSERT 成功 table={} 新id={} reason={}", table, id, audit);
                            return "✅ INSERT 执行成功，生成主键 id=" + id + "（表：" + table + "）。变更原因：" + audit;
                        })
                        .onErrorResume(e -> {
                            log.warn("[受控写] INSERT 失败 table={} reason={} -> {}", table, audit, e.getMessage());
                            return Mono.just("❌ INSERT 执行失败（表：" + table + "）：" + e.getMessage());
                        });
            } else {
                return r2dbcClient.execute(execSql, Collections.emptyMap())
                        .timeout(TIMEOUT)
                        .map(rows -> {
                            log.info("[受控写] UPDATE 成功 table={} 影响行数={} reason={}", table, rows, audit);
                            return "✅ UPDATE 执行成功，影响行数=" + rows + "（表：" + table + "）。变更原因：" + audit;
                        })
                        .onErrorResume(e -> {
                            log.warn("[受控写] UPDATE 失败 table={} reason={} -> {}", table, audit, e.getMessage());
                            return Mono.just("❌ UPDATE 执行失败（表：" + table + "）：" + e.getMessage());
                        });
            }
        } catch (Exception e) {
            log.warn("execute_write 异常: {}", e.getMessage());
            return Mono.just("SQL 执行异常：" + e.getMessage());
        }
    }

    private static String refuse(String msg) {
        return "⛔ 安全限制（受控写工具已拒绝）：" + msg;
    }

    private static String firstWord(String upper) {
        int i = upper.indexOf(' ');
        return (i < 0) ? upper : upper.substring(0, i);
    }

    private static String extractTable(String stmt) {
        Matcher m = Pattern.compile("(?i)(?:INSERT\\s+INTO|UPDATE)\\s+`?([a-zA-Z0-9_]+)`?").matcher(stmt);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 内部辅助 ====================

    /** 对每一行做向量列友好化（超长向量 JSON 折叠为摘要） */
    private static Map<String, Object> friendlyRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : row.entrySet()) {
            out.put(e.getKey(), summarizeVector(e.getValue()));
        }
        return out;
    }

    private static Object summarizeVector(Object v) {
        if (v instanceof String s && s.trim().startsWith("[")) {
            String t = s.trim();
            if (t.length() > 160) {
                try {
                    JSONArray arr = JSONUtil.parseArray(t);
                    int n = arr.size();
                    StringBuilder head = new StringBuilder("[");
                    int take = Math.min(5, n);
                    for (int i = 0; i < take; i++) {
                        head.append(arr.get(i)).append(i < take - 1 ? "," : "");
                    }
                    return "⟨vector dim=" + n + " preview=" + head + "...⟩ (已省略全文)";
                } catch (Exception ignored) {
                    // 解析失败则原样返回
                }
            }
        }
        return v;
    }

    private static float[] parseVector(String json) {
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                v[i] = (o instanceof Number n) ? n.floatValue() : Float.parseFloat(o.toString());
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseMeta(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> m = JSONUtil.toBean(JSONUtil.parseObj(json), Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
