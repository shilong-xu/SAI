package xsl.sai.schedule.mapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.R2dbcClient;
import xsl.sai.schedule.domain.ScheduleTask;

import java.util.HashMap;
import java.util.Map;

/** 查询列（与表结构一致） */
final class ScheduleTaskCols {
    static final String SELECT = "id, name, group_name, description, invoke_target, cron, timeout, "
            + "concurrent, status, create_time, update_time, remark, is_delete";
    private ScheduleTaskCols() {}
}

/**
 * 定时任务定义数据访问层 —— 基于 framework 的 {@link R2dbcClient}（响应式 R2DBC）
 *
 * @author SAI
 */
@Slf4j
@Repository
public class ScheduleTaskMapper {

    private final R2dbcClient r2dbcClient;

    public ScheduleTaskMapper(R2dbcClient r2dbcClient) {
        this.r2dbcClient = r2dbcClient;
    }

    /**
     * 查询所有「未删除 + 启用」的任务，供启动期注册调度器使用
     */
    public Flux<ScheduleTask> findEnabledTasks() {
        String sql = "SELECT id, name, group_name, description, invoke_target, cron, timeout, "
                + "concurrent, status, create_time, update_time, remark, is_delete "
                + "FROM schedule_task WHERE is_delete = 0 AND status = 1";
        return r2dbcClient.queryList(sql, Map.of(), ScheduleTask.class);
    }

    /**
     * 根据 id 查询单条任务
     */
    public Mono<ScheduleTask> findById(long id) {
        String sql = "SELECT id, name, group_name, description, invoke_target, cron, timeout, "
                + "concurrent, status, create_time, update_time, remark, is_delete "
                + "FROM schedule_task WHERE id = :id AND is_delete = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        return r2dbcClient.queryOne(sql, params, ScheduleTask.class);
    }

    /**
     * 分页查询任务（未删除）
     */
    public Flux<ScheduleTask> page(long offset, long size) {
        String sql = "SELECT " + ScheduleTaskCols.SELECT
                + " FROM schedule_task WHERE is_delete = 0 ORDER BY create_time DESC, id DESC LIMIT :limit OFFSET :offset";
        Map<String, Object> params = new HashMap<>();
        params.put("limit", size);
        params.put("offset", offset);
        return r2dbcClient.queryList(sql, params, ScheduleTask.class);
    }

    /**
     * 任务总数（未删除）
     */
    public Mono<Long> count() {
        String sql = "SELECT COUNT(*) AS cnt FROM schedule_task WHERE is_delete = 0";
        return r2dbcClient.queryOne(sql, Map.of(), Map.class)
                .map(m -> {
                    Object v = m.get("cnt");
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                })
                .defaultIfEmpty(0L);
    }

    /**
     * 按分组分页查询（未删除），支持按任务名模糊搜索
     */
    public Flux<ScheduleTask> pageByGroup(String groupName, String keyword, long offset, long size) {
        StringBuilder sql = new StringBuilder("SELECT " + ScheduleTaskCols.SELECT
                + " FROM schedule_task WHERE is_delete = 0 AND group_name = :groupName");
        Map<String, Object> params = new HashMap<>();
        params.put("groupName", groupName);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND name LIKE :kw");
            params.put("kw", "%" + keyword + "%");
        }
        sql.append(" ORDER BY create_time DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", size);
        params.put("offset", offset);
        return r2dbcClient.queryList(sql.toString(), params, ScheduleTask.class);
    }

    /**
     * 按分组统计（未删除），支持按任务名模糊搜索
     */
    public Mono<Long> countByGroup(String groupName, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS cnt FROM schedule_task WHERE is_delete = 0 AND group_name = :groupName");
        Map<String, Object> params = new HashMap<>();
        params.put("groupName", groupName);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND name LIKE :kw");
            params.put("kw", "%" + keyword + "%");
        }
        return r2dbcClient.queryOne(sql.toString(), params, Map.class)
                .map(m -> {
                    Object v = m.get("cnt");
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                })
                .defaultIfEmpty(0L);
    }

    /**
     * 新增任务
     */
    public Mono<Long> insert(ScheduleTask task) {
        String sql = "INSERT INTO schedule_task "
                + "(name, group_name, description, invoke_target, cron, timeout, concurrent, status, remark) "
                + "VALUES (:name, :groupName, :description, :invokeTarget, :cron, :timeout, :concurrent, :status, :remark)";
        Map<String, Object> params = new HashMap<>();
        params.put("name", task.getName());
        params.put("groupName", task.getGroupName());
        params.put("description", task.getDescription());
        params.put("invokeTarget", task.getInvokeTarget());
        params.put("cron", task.getCron());
        params.put("timeout", task.getTimeout());
        params.put("concurrent", task.getConcurrent());
        params.put("status", task.getStatus());
        params.put("remark", task.getRemark());
        // 返回数据库生成的自增主键（create 接口需要真实 id 供后续 detail/update/run/delete 使用）
        return r2dbcClient.executeReturnId(sql, params);
    }

    /**
     * 更新任务全字段（除主键与 is_delete）
     */
    public Mono<Long> update(ScheduleTask task) {
        String sql = "UPDATE schedule_task SET "
                + "name = :name, group_name = :groupName, description = :description, "
                + "invoke_target = :invokeTarget, cron = :cron, timeout = :timeout, "
                + "concurrent = :concurrent, status = :status, remark = :remark, update_time = NOW() "
                + "WHERE id = :id AND is_delete = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("id", task.getId());
        params.put("name", task.getName());
        params.put("groupName", task.getGroupName());
        params.put("description", task.getDescription());
        params.put("invokeTarget", task.getInvokeTarget());
        params.put("cron", task.getCron());
        params.put("timeout", task.getTimeout());
        params.put("concurrent", task.getConcurrent());
        params.put("status", task.getStatus());
        params.put("remark", task.getRemark());
        return r2dbcClient.execute(sql, params);
    }

    /**
     * 逻辑删除任务（按主键置 is_delete = 1）
     * <p>
     * 原 {@code setIsDelete(1)} + {@link #update(ScheduleTask)} 方案不生效，原因有二：
     * 1. {@link #update(ScheduleTask)} 的 SET 子句不含 {@code is_delete} 列，标记无法写回库；
     * 2. {@code R2dbcClient} 经 {@code JSONUtil.toBean} 映射时，DB 列 {@code is_delete} 与实体属性
     *    {@code isDelete} 驼峰不一致，查出的 {@code isDelete} 恒为默认值，通用 {@code update} 还可能误写回 0。
     * 故删除单独用本方法直接 UPDATE 标记位，不影响其它查询（均 {@code WHERE is_delete = 0}）。
     */
    public Mono<Long> logicDeleteById(long id) {
        String sql = "UPDATE schedule_task SET is_delete = 1, update_time = NOW() "
                + "WHERE id = :id AND is_delete = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        return r2dbcClient.execute(sql, params);
    }
}
