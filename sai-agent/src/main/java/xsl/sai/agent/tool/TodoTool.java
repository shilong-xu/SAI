package xsl.sai.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.framework.enums.TodoType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办事项工具 —— 供 Agent 读写「待办事项」（对应前端「协作空间 → 待办事项」页面维护的 todo_item 表）。
 *
 * <p>三个工具：
 * <ul>
 *   <li>{@code query_todo}：按关键词 / 完成状态查询待办，用于回答「有哪些待办」以及<b>入库前的去重判断</b>；</li>
 *   <li>{@code save_todo}：保存一条待办（标题 + 类型 + Markdown 内容）；对<b>同标题</b>的未删除记录做幂等去重，不会重复创建；</li>
 *   <li>{@code update_todo_status}：把待办标记为已完成 / 未完成（按标题定位，也可用 ID）。</li>
 * </ul>
 *
 * <p><b>类型字段（{@code type}）落库为数字 code</b>：见 {@link TodoType}
 * （{@code 1=任务 2=事项 3=会议 4=回信}）。工具入参保持文本，内部用 {@link TodoType#parse(Object)}
 * 归一 —— 模型写 {@code "3"}、{@code "会议"} 都能正确落库。
 *
 * <p><b>面向用户的输出不含主键 ID</b>：本类所有工具返回的文本都是给模型、并可能被原样转述给用户的，
 * 而数据库自增 ID 对用户没有任何意义（用户只按「标题 + 状态」识别待办）。
 * 因此查询列表不输出编号，保存 / 改状态的回执也用<b>标题</b>指代目标；
 * ID 只在工具内部用于 SQL 定位，<b>不要出现在对话里</b>。
 *
 * <p><b>为什么直连 SQL 而不用 client 层的 Service</b>：模块依赖是 {@code sai-client → sai-agent}（client 依赖 agent），
 * agent 模块拿不到 client 的 Service；与 {@code KnowledgeBaseTool} 一样，本类用 {@link R2dbcClient} 直连同库同表，
 * 与页面维护的是<b>同一份数据</b>。
 *
 * <p><b>不给模型开放删除</b>：删除是破坏性动作、无人值守场景无人复核，故只提供增 / 查 / 改状态三个工具。
 *
 * <p><b>纯流式、零阻塞</b>：全部返回 {@link Mono<String>}，底层 R2DBC 响应式，不使用 {@code .block()}。
 *
 * @author SAI
 */
@Slf4j
@Component
public class TodoTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    /** 标题长度上限（与 todo_item.title varchar(500) 对齐） */
    private static final int MAX_TITLE = 500;
    /** 列表里内容预览的字符数（与 SQL 的 LEFT(content, 200) 对齐） */
    private static final int PREVIEW_CHARS = 200;

    private final R2dbcClient r2dbcClient;

    public TodoTool(R2dbcClient r2dbcClient) {
        this.r2dbcClient = r2dbcClient;
    }

    // ==================== 查询 ====================

    @Tool(name = "query_todo",
            description = "查询【待办事项 todo_item 表】（前端「协作空间 → 待办事项」页面同源）。"
                    + "按关键词模糊匹配标题 / 内容，可按完成状态筛选，按「未完成优先 + 更新时间倒序」返回。"
                    + "用于回答「我有哪些待办 / 某件事登记了吗 / 某条待办完成了没」等问题，"
                    + "也是保存待办前的去重依据（先查是否已存在同标题待办，再决定要不要新建）。"
                    + "返回内容不含主键 ID，向用户描述时直接说标题与状态即可，不要提及编号。"
                    + "参数：keyword 关键词（可空）；status 取 all/todo/done（可空，默认 all）；limit 返回条数（可空，默认20，最大50）。")
    public Mono<String> queryTodo(
            RuntimeContext runtimeContext,
            @ToolParam(name = "keyword", required = false,
                    description = "关键词，模糊匹配标题或内容；留空表示不筛选") String keyword,
            @ToolParam(name = "status", required = false,
                    description = "状态筛选：all=全部（默认）、todo=仅未完成、done=仅已完成") String status,
            @ToolParam(name = "limit", required = false,
                    description = "返回条数上限，默认 20，最大 50") Integer limit) {

        final String kw = blankToNull(keyword);
        final Integer doneFilter = parseStatus(status);
        int lim = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 刻意不查 id：查询结果会直接呈现给用户，主键 ID 对用户无意义
        StringBuilder sql = new StringBuilder(
                "SELECT title, type, done, "
                        + "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i') AS update_text, "
                        + "LEFT(content, " + PREVIEW_CHARS + ") AS content_preview "
                        + "FROM todo_item WHERE is_delete = 0");
        Map<String, Object> p = new LinkedHashMap<>(2);
        if (kw != null) {
            // 同一个命名参数出现两次，R2dbcClient 会按出现顺序各绑一次，行为正确
            sql.append(" AND (title LIKE :kw OR content LIKE :kw)");
            p.put("kw", "%" + kw + "%");
        }
        if (doneFilter != null) {
            sql.append(" AND done = :done");
            p.put("done", doneFilter);
        }
        // LIMIT 为已校验的 int，直接拼接安全（命名参数无法用于 LIMIT）
        sql.append(" ORDER BY done ASC, update_time DESC, id DESC LIMIT ").append(lim);

        return r2dbcClient.queryList(sql.toString(), p, Map.class)
                .collectList()
                .timeout(TIMEOUT)
                .map(rows -> formatRows(rows, kw, doneFilter))
                .onErrorResume(e -> {
                    log.warn("query_todo 失败: {}", e.getMessage());
                    return Mono.just("⛔ 待办查询失败：" + e.getMessage());
                });
    }

    // ==================== 保存 ====================

    @Tool(name = "save_todo",
            description = "保存一条【待办事项】（写入 todo_item 表，前端「协作空间 → 待办事项」页面可见）。"
                    + "适用场景：用户要求「记个待办 / 提醒我 / 把这件事加入待办」，"
                    + "或邮件等外部信息经判定后需要登记为待办。"
                    + "参数：title 必填（一句话说清要做什么，尽量短）；"
                    + "type 为类型，取值 1=任务 / 2=事项 / 3=会议 / 4=回信（也接受中文标签），可空（默认 2=事项）；"
                    + "content 为 Markdown 正文，可空，写清背景 / 要求 / 关键信息（支持标题、列表、表格等）。"
                    + "幂等：若已存在同标题的未删除待办，不会重复创建，直接告知已存在（回执用标题指代，不含编号）。")
    public Mono<String> saveTodo(
            RuntimeContext runtimeContext,
            @ToolParam(name = "title", required = true,
                    description = "待办标题（一句话，尽量短）") String title,
            @ToolParam(name = "type", required = false,
                    description = "类型：1=任务 / 2=事项 / 3=会议 / 4=回信（也接受中文标签 任务/事项/会议/回信）；留空默认 2=事项") String type,
            @ToolParam(name = "content", required = false,
                    description = "待办正文（Markdown 文本，可空）") String content) {

        final String t = blankToNull(title);
        if (t == null) {
            return Mono.just("⛔ 保存失败：title 不能为空。");
        }
        if (t.length() > MAX_TITLE) {
            return Mono.just("⛔ 保存失败：标题过长（" + t.length() + " 字），请控制在 " + MAX_TITLE + " 字以内。");
        }
        // 类型：数字 code 优先解析，无法识别（含留空）一律落到默认值「事项」
        final Integer parsed = TodoType.parse(type);
        final int typeCode = (parsed == null) ? TodoType.DEFAULT.getCode() : parsed;
        final String typeLabel = TodoType.labelOf(typeCode);
        final String ct = blankToNull(content);

        // 幂等去重：同标题的未删除待办只保留一条，避免同一封邮件被重复登记
        Map<String, Object> q = Map.of("title", t);
        return r2dbcClient.queryOne(
                        "SELECT done FROM todo_item WHERE is_delete = 0 AND title = :title "
                                + "ORDER BY id DESC LIMIT 1", q, Map.class)
                .timeout(TIMEOUT)
                .flatMap(existing -> {
                    String state = doneFlag(existing.get("done")) == 1 ? "已完成" : "未完成";
                    return Mono.just("ℹ️ 已存在同标题待办「" + t + "」（当前" + state + "，标题与内容未改动），未重复创建。"
                            + "如需变更内容，请先与用户确认后再处理。");
                })
                .switchIfEmpty(Mono.defer(() -> insert(t, typeCode, ct)))
                .onErrorResume(e -> {
                    log.warn("save_todo 失败: title={}, err={}", t, e.getMessage());
                    return Mono.just("⛔ 保存待办失败：" + e.getMessage());
                });
    }

    private Mono<String> insert(String title, int typeCode, String content) {
        Map<String, Object> p = new LinkedHashMap<>(3);
        p.put("title", title);
        p.put("type", typeCode);
        p.put("content", content);
        return r2dbcClient.executeReturnId(
                        "INSERT INTO todo_item (title, type, content, done, is_delete) "
                                + "VALUES (:title, :type, :content, 0, 0)", p)
                .timeout(TIMEOUT)
                .map(id -> "✅ 已保存待办「" + title + "」（类型="
                        + TodoType.labelOf(typeCode) + "，状态=未完成）。");
    }

    // ==================== 改状态 ====================

    @Tool(name = "update_todo_status",
            description = "编辑【待办事项】的完成状态（把待办标记为已完成 / 未完成）。"
                    + "适用场景：用户说「XX 已经做完了 / 把那条待办勾掉 / 那个还没做完」。"
                    + "定位方式：直接用 title（待办标题，精确匹配，推荐 —— query_todo 的结果里就有标题）；"
                    + "id 仅在本次会话中已明确知晓时才用（查询结果不返回 ID，一般用不到）。"
                    + "本工具只改状态，不修改标题与内容，也不删除待办。"
                    + "回执以标题指代目标，向用户回复时不要提及编号。")
    public Mono<String> updateTodoStatus(
            RuntimeContext runtimeContext,
            @ToolParam(name = "title", required = false,
                    description = "待办标题（精确匹配，推荐用这个定位）") String title,
            @ToolParam(name = "id", required = false,
                    description = "待办 ID（可选，仅在你已明确知道 ID 时使用；一般用 title 定位）") Long id,
            @ToolParam(name = "done", required = true,
                    description = "true=标记为已完成，false=标记为未完成") Boolean done) {

        final String t = blankToNull(title);
        if (id == null && t == null) {
            return Mono.just("⛔ 操作失败：请提供 title（或 id）以定位待办。");
        }
        final int flag = (done != null && done) ? 1 : 0;
        final String stateText = flag == 1 ? "已完成" : "未完成";

        // 先定位目标（同时取回标题，便于回执中用标题指代，不暴露 ID）
        // 注意：R2dbcClient#queryOne 返回 Mono<T>，传 Map.class 时 T 推定是原生 Map，故此处用 Mono<Map>
        Mono<Map> targetMono;
        if (id != null) {
            targetMono = r2dbcClient.queryOne(
                    "SELECT id, title FROM todo_item WHERE id = :id AND is_delete = 0 LIMIT 1",
                    Map.of("id", id), Map.class);
        } else {
            // 按标题定位：同标题取最新一条（标题有歧义时由模型先用 query_todo 核对）
            targetMono = r2dbcClient.queryOne(
                    "SELECT id, title FROM todo_item WHERE is_delete = 0 AND title = :title "
                            + "ORDER BY id DESC LIMIT 1", Map.of("title", t), Map.class);
        }

        return targetMono
                .timeout(TIMEOUT)
                .flatMap(row -> {
                    final Long tid = toLong(row.get("id"));
                    final String rowTitle = row.get("title") == null ? "(无标题)" : row.get("title").toString();
                    Map<String, Object> p = new LinkedHashMap<>(2);
                    p.put("done", flag);
                    p.put("id", tid);
                    return r2dbcClient.execute(
                                    "UPDATE todo_item SET done = :done, update_time = NOW(3) "
                                            + "WHERE id = :id AND is_delete = 0", p)
                            .timeout(TIMEOUT)
                            .map(rows -> {
                                if (rows == null || rows == 0L) {
                                    return "⚠️ 未找到待办「" + rowTitle + "」（可能已被删除），状态未变更。";
                                }
                                return "✅ 已把待办「" + rowTitle + "」标记为「" + stateText + "」。";
                            });
                })
                .switchIfEmpty(Mono.fromSupplier(() -> id != null
                        ? "⚠️ 未找到该待办（可能已被删除），状态未变更。"
                        : "⚠️ 未找到标题为「" + t + "」的待办，状态未变更。"))
                .onErrorResume(e -> {
                    log.warn("update_todo_status 失败: id={}, title={}, err={}", id, title, e.getMessage());
                    return Mono.just("⛔ 修改待办状态失败：" + e.getMessage());
                });
    }

    // ==================== 内部辅助 ====================

    /** status → done 过滤值；all / 空 返回 null（不过滤） */
    private static Integer parseStatus(String status) {
        String s = status == null ? "" : status.trim().toLowerCase();
        return switch (s) {
            case "todo", "undone", "pending", "0" -> 0;
            case "done", "finished", "1" -> 1;
            default -> null;
        };
    }

    /** 空白（null / 空串 / 全空白）→ null */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** done 列（tinyint / Boolean）统一转 0 / 1 */
    private static int doneFlag(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return n.intValue() == 1 ? 1 : 0;
        }
        String s = v.toString().trim();
        return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? 1 : 0;
    }

    /** id 列（Number / String）统一转 Long */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** type 列（tinyint code）→ 中文标签；未指定或越界返回 "-" */
    private static String typeLabel(Object v) {
        if (v == null) {
            return "-";
        }
        Integer code;
        if (v instanceof Number n) {
            code = n.intValue();
        } else {
            try {
                code = Integer.valueOf(v.toString().trim());
            } catch (NumberFormatException e) {
                return "-";
            }
        }
        String label = TodoType.labelOf(code);
        return label == null ? "-" : label;
    }

    /**
     * 把查询结果格式化为紧凑文本（未完成优先，含类型 / 状态 / 更新时间 / 内容预览）。
     *
     * <p><b>刻意不输出主键 ID</b>：查询结果常常被模型原样转述给用户，而自增 ID 对用户没有任何意义，
     * 用户是按「标题 + 状态」识别待办的；改状态也只需标题即可定位。
     */
    private String formatRows(List<Map> rows, String keyword, Integer doneFilter) {
        if (rows == null || rows.isEmpty()) {
            StringBuilder sb = new StringBuilder("没有查询到待办事项");
            if (keyword != null) {
                sb.append("（关键词「").append(keyword).append("」）");
            }
            if (doneFilter != null) {
                sb.append("（状态：").append(doneFilter == 1 ? "已完成" : "未完成").append("）");
            }
            return sb.append("。").toString();
        }
        int doneCount = 0;
        List<String> lines = new ArrayList<>(rows.size());
        for (Map row : rows) {
            int done = doneFlag(row.get("done"));
            doneCount += done;
            String type = typeLabel(row.get("type"));
            String time = row.get("update_text") == null ? "-" : row.get("update_text").toString();
            String preview = row.get("content_preview") == null ? "" : row.get("content_preview").toString()
                    .replace("\r", " ").replace("\n", " ").trim();
            StringBuilder line = new StringBuilder();
            line.append(done == 1 ? "已完成" : "未完成")
                    .append(" | ").append(type)
                    .append(" | ").append(row.get("title") == null ? "(无标题)" : row.get("title"))
                    .append("  (更新 ").append(time).append(")");
            if (!preview.isEmpty()) {
                line.append("\n      内容：").append(preview)
                        .append(preview.length() >= PREVIEW_CHARS ? "…" : "");
            }
            lines.add(line.toString());
        }
        return "查询到 " + rows.size() + " 条待办（未完成 " + (rows.size() - doneCount) + " / 已完成 " + doneCount + "）：\n"
                + String.join("\n", lines);
    }
}
