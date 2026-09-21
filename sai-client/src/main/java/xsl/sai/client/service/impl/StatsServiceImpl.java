package xsl.sai.client.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xsl.sai.client.mapper.KnowledgeBaseMapper;
import xsl.sai.client.mapper.MessageMapper;
import xsl.sai.client.mapper.ReceivedEmailMapper;
import xsl.sai.client.mapper.TodoItemMapper;
import xsl.sai.client.pojo.vo.DashboardVO;
import xsl.sai.client.service.StatsService;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.schedule.mapper.ScheduleLogMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计 服务实现
 * <p>所有统计基于未删除记录，全局口径（不按用户隔离）；近 7 日趋势按日期对齐补零。
 *
 * <p>近 7 日趋势含四路指标：Token 消耗（client_message）+ 邮件收发（received_email）
 * + 调度执行（schedule_log）+ 待办新增（todo_item），四路各自按日聚合后在内存里按日期合并为同一条 series。
 *
 * @author SAI
 */
@Slf4j
@Service
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MessageMapper messageMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ScheduleLogMapper scheduleLogMapper;
    private final ReceivedEmailMapper receivedEmailMapper;
    private final TodoItemMapper todoItemMapper;
    private final R2dbcClient r2dbcClient;
    private final Environment environment;

    public StatsServiceImpl(MessageMapper messageMapper,
                            KnowledgeBaseMapper knowledgeBaseMapper,
                            ScheduleLogMapper scheduleLogMapper,
                            ReceivedEmailMapper receivedEmailMapper,
                            TodoItemMapper todoItemMapper,
                            R2dbcClient r2dbcClient,
                            Environment environment) {
        this.messageMapper = messageMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.scheduleLogMapper = scheduleLogMapper;
        this.receivedEmailMapper = receivedEmailMapper;
        this.todoItemMapper = todoItemMapper;
        this.r2dbcClient = r2dbcClient;
        this.environment = environment;
    }

    @Override
    public Mono<DashboardVO> dashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();

        // 近 7 日趋势：Token 与邮件两路各自按日聚合（缺数据的日期在合并阶段补 0）
        // 走 R2dbcClient 原生 SQL 查 Map，绕开 Repository 接口投影对 DATE_FORMAT 列名映射失效的问题
        Mono<Map<String, Long>> tokenByDay = r2dbcClient.queryList(
                        "SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDate, IFNULL(SUM(tokens), 0) AS statCount "
                                + "FROM client_message WHERE is_delete = 0 AND create_time >= :start "
                                + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')",
                        Map.of("start", weekStart), Map.class)
                .collectMap(row -> String.valueOf(row.get("statDate")), row -> toLong(row.get("statCount")));

        Mono<Map<String, Long>> emailByDay = r2dbcClient.queryList(
                        "SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDate, COUNT(*) AS statCount "
                                + "FROM received_email WHERE is_delete = 0 AND create_time >= :start "
                                + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')",
                        Map.of("start", weekStart), Map.class)
                .collectMap(row -> String.valueOf(row.get("statDate")), row -> toLong(row.get("statCount")));

        Mono<Map<String, Long>> schedByDay = scheduleLogMapper.countByDaySince(weekStart);

        // 待办新增：按 create_time 聚合（与邮件同口径，衡量「新增量」而非「完成量」）
        Mono<Map<String, Long>> todoByDay = r2dbcClient.queryList(
                        "SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDate, COUNT(*) AS statCount "
                                + "FROM todo_item WHERE is_delete = 0 AND create_time >= :start "
                                + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')",
                        Map.of("start", weekStart), Map.class)
                .collectMap(row -> String.valueOf(row.get("statDate")), row -> toLong(row.get("statCount")));

        Mono<List<DashboardVO.DailyItem>> series = Mono.zip(tokenByDay, emailByDay, schedByDay, todoByDay)
                .map(t -> {
                    Map<String, Long> tokenMap = new HashMap<>(t.getT1());
                    Map<String, Long> emailMap = new HashMap<>(t.getT2());
                    Map<String, Long> schedMap = new HashMap<>(t.getT3());
                    Map<String, Long> todoMap = new HashMap<>(t.getT4());
                    List<DashboardVO.DailyItem> list = new ArrayList<>(7);
                    for (int i = 6; i >= 0; i--) {
                        String day = today.minusDays(i).format(DAY_FMT);
                        list.add(DashboardVO.DailyItem.builder()
                                .date(day)
                                .tokens(tokenMap.getOrDefault(day, 0L))
                                .emails(emailMap.getOrDefault(day, 0L))
                                .schedules(schedMap.getOrDefault(day, 0L))
                                .todos(todoMap.getOrDefault(day, 0L))
                                .build());
                    }
                    return list;
                });

        // 第一组：知识库统计（2 项）+ 趋势
        Mono<KnowledgeStats> knowledge = Mono.zip(
                        knowledgeBaseMapper.countAll(),
                        knowledgeBaseMapper.countChunksAll(),
                        series)
                .map(t -> KnowledgeStats.of(t.getT1(), t.getT2(), t.getT3()));

        // 第二组：调度统计（3 项）
        Mono<ScheduleStats> schedule = Mono.zip(
                        scheduleLogMapper.countBetween(todayStart, tomorrowStart),
                        scheduleLogMapper.countAll(),
                        scheduleLogMapper.countAllSuccess())
                .map(t -> ScheduleStats.of(t.getT1(), t.getT2(), t.getT3()));

        // 第三组：邮件统计（4 项）
        Mono<EmailStats> email = Mono.zip(
                        receivedEmailMapper.countByDirection(0),
                        receivedEmailMapper.countByDirection(1),
                        receivedEmailMapper.countPending(),
                        receivedEmailMapper.countBetween(todayStart, tomorrowStart))
                .map(t -> EmailStats.of(t.getT1(), t.getT2(), t.getT3(), t.getT4()));

        // 第四组：待办统计（3 项：总数 / 未完成 / 今日新增）
        Mono<TodoStats> todo = Mono.zip(
                        todoItemMapper.countAll(),
                        todoItemMapper.countActive(),
                        todoItemMapper.countBetween(todayStart, tomorrowStart))
                .map(t -> TodoStats.of(t.getT1(), t.getT2(), t.getT3()));

        // 最新 2 条预览（3 个模块并行，走 R2dbcClient 原生 SQL 查 Map，绕开 Repository 投影限制）
        Mono<List<DashboardVO.PreviewItem>> kbRecent = r2dbcClient.queryList(
                        "SELECT LEFT(content, 50) AS title, remark AS subtitle, NULL AS extra, "
                                + "DATE_FORMAT(update_time, '%Y-%m-%d') AS time "
                                + "FROM knowledge_base WHERE is_delete = 0 ORDER BY update_time DESC LIMIT :limit",
                        Map.of("limit", 2), Map.class)
                .map(this::toPreview)
                .collectList().defaultIfEmpty(Collections.emptyList());
        Mono<List<DashboardVO.PreviewItem>> schedRecent = scheduleLogMapper.findLatest(2)
                .map(list -> list.stream().map(m -> DashboardVO.PreviewItem.builder()
                        .title(m.get("title")).subtitle(m.get("subtitle"))
                        .extra(m.get("extra")).time(m.get("time")).build())
                        .toList())
                .defaultIfEmpty(Collections.emptyList());
        Mono<List<DashboardVO.PreviewItem>> emailRecent = r2dbcClient.queryList(
                        "SELECT LEFT(IFNULL(subject, '(无主题)'), 50) AS title, "
                                + "IFNULL(NULLIF(from_name, ''), from_addr) AS subtitle, "
                                + "CASE direction WHEN 0 THEN '收件' ELSE '发件' END AS extra, "
                                + "DATE_FORMAT(create_time, '%Y-%m-%d') AS time "
                                + "FROM received_email WHERE is_delete = 0 ORDER BY create_time DESC LIMIT :limit",
                        Map.of("limit", 2), Map.class)
                .map(this::toPreview)
                .collectList().defaultIfEmpty(Collections.emptyList());

        Mono<List<DashboardVO.PreviewItem>> todoRecent = r2dbcClient.queryList(
                        "SELECT LEFT(title, 50) AS title, "
                                // type 已改为 tinyint code（见 TodoType：1任务 2事项 3会议 4回信），此处直接映射中文
                                + "CASE type WHEN 1 THEN '任务' WHEN 2 THEN '事项' "
                                + "WHEN 3 THEN '会议' WHEN 4 THEN '回信' ELSE '—' END AS subtitle, "
                                + "CASE done WHEN 0 THEN '未完成' ELSE '已完成' END AS extra, "
                                + "DATE_FORMAT(update_time, '%Y-%m-%d') AS time "
                                + "FROM todo_item WHERE is_delete = 0 ORDER BY update_time DESC LIMIT :limit",
                        Map.of("limit", 2), Map.class)
                .map(this::toPreview)
                .collectList().defaultIfEmpty(Collections.emptyList());

        // 预览合集（4 路并行聚合）
        Mono<Previews> previews = Mono.zip(kbRecent, schedRecent, emailRecent, todoRecent)
                .map(q -> new Previews(q.getT1(), q.getT2(), q.getT3(), q.getT4()));

        // 汇总：Token 2 项 + 四组中间聚合体 + 预览合集
        return Mono.zip(
                        messageMapper.sumTokensBetween(todayStart, tomorrowStart),
                        messageMapper.sumTokensAll(),
                        knowledge,
                        schedule,
                        email,
                        todo,
                        previews)
                .map(t -> DashboardVO.builder()
                        .model(environment.getProperty("agentScope.models.modelName", "未配置"))
                        .todayTokens(t.getT1())
                        .totalTokens(t.getT2())
                        .knowledgeTotal(t.getT3().knowledgeTotal)
                        .knowledgeChunks(t.getT3().knowledgeChunks)
                        .series(t.getT3().series)
                        .scheduleToday(t.getT4().scheduleToday)
                        .scheduleTotal(t.getT4().scheduleTotal)
                        .scheduleSuccess(t.getT4().scheduleSuccess)
                        .emailInbox(t.getT5().emailInbox)
                        .emailSent(t.getT5().emailSent)
                        .emailUnsummarized(t.getT5().emailUnsummarized)
                        .emailToday(t.getT5().emailToday)
                        .todoTotal(t.getT6().todoTotal)
                        .todoActive(t.getT6().todoActive)
                        .todoToday(t.getT6().todoToday)
                        .knowledgeRecent(t.getT7().kb)
                        .scheduleRecent(t.getT7().schedule)
                        .emailRecent(t.getT7().email)
                        .todoRecent(t.getT7().todo)
                        .build());
    }

    /** 最新记录预览合集 */
    private record Previews(List<DashboardVO.PreviewItem> kb,
                            List<DashboardVO.PreviewItem> schedule,
                            List<DashboardVO.PreviewItem> email,
                            List<DashboardVO.PreviewItem> todo) {
    }

    /** Map → PreviewItem（容错 null 值，统一转字符串；接受 raw Map 兼容 R2dbcClient 返回） */
    @SuppressWarnings("unchecked")
    private DashboardVO.PreviewItem toPreview(Object raw) {
        Map<String, Object> m = (Map<String, Object>) raw;
        return DashboardVO.PreviewItem.builder()
                .title(str(m.get("title")))
                .subtitle(str(m.get("subtitle")))
                .extra(str(m.get("extra")))
                .time(str(m.get("time")))
                .build();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Long toLong(Object v) {
        if (v == null) { return 0L; }
        if (v instanceof Number) { return ((Number) v).longValue(); }
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; }
    }

    /** 知识库统计中间聚合体（配合 Mono.zip 元组上限拆分） */
    private static final class KnowledgeStats {
        final Long knowledgeTotal;
        final Long knowledgeChunks;
        final List<DashboardVO.DailyItem> series;

        private KnowledgeStats(Long knowledgeTotal, Long knowledgeChunks,
                               List<DashboardVO.DailyItem> series) {
            this.knowledgeTotal = knowledgeTotal;
            this.knowledgeChunks = knowledgeChunks;
            this.series = series;
        }

        static KnowledgeStats of(Long knowledgeTotal, Long knowledgeChunks,
                                 List<DashboardVO.DailyItem> series) {
            return new KnowledgeStats(knowledgeTotal, knowledgeChunks, series);
        }
    }

    /** 调度统计中间聚合体 */
    private static final class ScheduleStats {
        final Long scheduleToday;
        final Long scheduleTotal;
        final Long scheduleSuccess;

        private ScheduleStats(Long scheduleToday, Long scheduleTotal, Long scheduleSuccess) {
            this.scheduleToday = scheduleToday;
            this.scheduleTotal = scheduleTotal;
            this.scheduleSuccess = scheduleSuccess;
        }

        static ScheduleStats of(Long scheduleToday, Long scheduleTotal, Long scheduleSuccess) {
            return new ScheduleStats(scheduleToday, scheduleTotal, scheduleSuccess);
        }
    }

    /** 邮件统计中间聚合体 */
    private static final class EmailStats {
        final Long emailInbox;
        final Long emailSent;
        final Long emailUnsummarized;
        final Long emailToday;

        private EmailStats(Long emailInbox, Long emailSent, Long emailUnsummarized, Long emailToday) {
            this.emailInbox = emailInbox;
            this.emailSent = emailSent;
            this.emailUnsummarized = emailUnsummarized;
            this.emailToday = emailToday;
        }

        static EmailStats of(Long emailInbox, Long emailSent, Long emailUnsummarized, Long emailToday) {
            return new EmailStats(emailInbox, emailSent, emailUnsummarized, emailToday);
        }
    }

    /** 待办统计中间聚合体 */
    private static final class TodoStats {
        final Long todoTotal;
        final Long todoActive;
        final Long todoToday;

        private TodoStats(Long todoTotal, Long todoActive, Long todoToday) {
            this.todoTotal = todoTotal;
            this.todoActive = todoActive;
            this.todoToday = todoToday;
        }

        static TodoStats of(Long todoTotal, Long todoActive, Long todoToday) {
            return new TodoStats(todoTotal, todoActive, todoToday);
        }
    }
}
