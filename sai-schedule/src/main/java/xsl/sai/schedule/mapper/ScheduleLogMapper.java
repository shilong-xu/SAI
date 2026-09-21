package xsl.sai.schedule.mapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.schedule.domain.ScheduleLog;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务执行日志数据访问层 —— 基于 framework 的 {@link R2dbcClient}
 *
 * @author SAI
 */
@Slf4j
@Repository
public class ScheduleLogMapper {

    private final R2dbcClient r2dbcClient;

    public ScheduleLogMapper(R2dbcClient r2dbcClient) {
        this.r2dbcClient = r2dbcClient;
    }

    /**
     * 写入一条执行日志
     */
    public Mono<Long> insert(ScheduleLog log) {
        String sql = "INSERT INTO schedule_log "
                + "(task_id, task_name, invoke_target, task_type, result, success, error_msg, cost_ms, "
                + "create_time, update_time, remark, is_delete) "
                + "VALUES (:taskId, :taskName, :invokeTarget, :taskType, :result, :success, :errorMsg, :costMs, "
                + ":createTime, :updateTime, :remark, 0)";
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", log.getTaskId());
        params.put("taskName", log.getTaskName());
        params.put("invokeTarget", log.getInvokeTarget());
        params.put("taskType", log.getTaskType());
        params.put("result", log.getResult());
        params.put("success", log.getSuccess());
        params.put("errorMsg", log.getErrorMsg());
        params.put("costMs", log.getCostMs());
        params.put("createTime", LocalDateTime.now());
        params.put("updateTime", LocalDateTime.now());
        params.put("remark", log.getRemark());
        return r2dbcClient.execute(sql, params);
    }

    /**
     * 查询某任务最近执行日志
     */
    public Flux<ScheduleLog> findByTask(long taskId, long limit) {
        String sql = "SELECT id, task_id, task_name, invoke_target, task_type, result, success, error_msg, "
                + "cost_ms, create_time, is_delete "
                + "FROM schedule_log WHERE task_id = :taskId AND is_delete = 0 "
                + "ORDER BY create_time DESC, id DESC LIMIT :limit";
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", taskId);
        params.put("limit", limit);
        return r2dbcClient.queryList(sql, params, ScheduleLog.class);
    }

    /**
     * 查询全部任务的最近执行日志（全局运行日志页面使用）
     *
     * @param limit  条数上限
     * @param type   可选的任务类型过滤（COMMON / AGENT），为空表示全部
     */
    public Flux<ScheduleLog> listRecent(long limit, String type) {
        String where = "is_delete = 0";
        if (type != null && !type.isEmpty()) {
            where += " AND task_type = :taskType";
        }
        String sql = "SELECT id, task_id, task_name, invoke_target, task_type, result, success, error_msg, "
                + "cost_ms, create_time, is_delete "
                + "FROM schedule_log WHERE " + where
                + " ORDER BY create_time DESC, id DESC LIMIT :limit";
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        if (type != null && !type.isEmpty()) {
            params.put("taskType", type);
        }
        return r2dbcClient.queryList(sql, params, ScheduleLog.class);
    }

    /**
     * 统计时间段内调度执行次数（start 含 / end 不含；仪表盘用）
     */
    public Mono<Long> countBetween(LocalDateTime start, LocalDateTime end) {
        String sql = "SELECT COUNT(*) AS cnt FROM schedule_log "
                + "WHERE is_delete = 0 AND create_time >= :start AND create_time < :end";
        Map<String, Object> params = new HashMap<>();
        params.put("start", start);
        params.put("end", end);
        return r2dbcClient.queryOne(sql, params, Map.class)
                .map(m -> {
                    Object v = m.get("cnt");
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                })
                .defaultIfEmpty(0L);
    }

    /**
     * 统计全部调度执行次数（仪表盘用）
     */
    public Mono<Long> countAll() {
        String sql = "SELECT COUNT(*) AS cnt FROM schedule_log WHERE is_delete = 0";
        return r2dbcClient.queryOne(sql, Map.of(), Map.class)
                .map(m -> {
                    Object v = m.get("cnt");
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                })
                .defaultIfEmpty(0L);
    }

    /**
     * 统计全部调度执行成功次数（仪表盘用，用于计算成功率）
     */
    public Mono<Long> countAllSuccess() {
        String sql = "SELECT COUNT(*) AS cnt FROM schedule_log WHERE is_delete = 0 AND success = 1";
        return r2dbcClient.queryOne(sql, Map.of(), Map.class)
                .map(m -> {
                    Object v = m.get("cnt");
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                })
                .defaultIfEmpty(0L);
    }

    /**
     * 按日聚合调度执行次数（仪表盘近 7 日趋势用）
     * <p>只返回有数据的日期；缺数据的日期由调用方补 0（避免 SQL 侧生成日期序列）。
     *
     * @param start 统计起始时间（含）
     * @return key = yyyy-MM-dd，value = 当日执行次数
     */
    public Mono<Map<String, Long>> countByDaySince(LocalDateTime start) {
        String sql = "SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDate, COUNT(*) AS statCount "
                + "FROM schedule_log WHERE is_delete = 0 AND create_time >= :start "
                + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')";
        return r2dbcClient.queryList(sql, Map.of("start", start), Map.class)
                .collectMap(m -> String.valueOf(m.get("statDate")),
                        m -> {
                            Object v = m.get("statCount");
                            return v instanceof Number ? ((Number) v).longValue() : 0L;
                        });
    }

    /**
     * 最新 N 条调度日志预览（仪表盘卡片用）
     * <p>返回字段：title=任务名, subtitle=成功/失败状态, extra=耗时ms, time=执行时间
     */
    public Mono<List<Map<String, String>>> findLatest(int limit) {
        String sql = "SELECT task_name, success, cost_ms, "
                + "DATE_FORMAT(create_time, '%m-%d %H:%i') AS fmt_time "
                + "FROM schedule_log WHERE is_delete = 0 ORDER BY create_time DESC, id DESC LIMIT :limit";
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        return r2dbcClient.queryList(sql, params, Map.class)
                .map(m -> {
                    Map<String, String> r = new HashMap<>();
                    r.put("title", String.valueOf(m.getOrDefault("task_name", "")));
                    Object succ = m.get("success");
                    r.put("subtitle", "1".equals(String.valueOf(succ)) ? "成功" : "失败");
                    r.put("extra", String.valueOf(m.getOrDefault("cost_ms", 0)) + "ms");
                    r.put("time", String.valueOf(m.getOrDefault("fmt_time", "")));
                    return r;
                })
                .collectList()
                .defaultIfEmpty(java.util.Collections.emptyList());
    }
}
