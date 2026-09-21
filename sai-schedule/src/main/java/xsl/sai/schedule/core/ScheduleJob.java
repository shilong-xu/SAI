package xsl.sai.schedule.core;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import xsl.sai.schedule.domain.ScheduleLog;
import xsl.sai.schedule.invoke.TaskInvoker;
import xsl.sai.schedule.mapper.ScheduleLogMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Quartz Job —— 通过 {@link TaskInvoker} 反射调用业务方法，并落库日志。
 *
 * <p>该 Job 由 {@code SpringBeanJobFactory} 创建，因此 {@code @Autowired} 字段可以注入。
 *
 * <p>并发 / 超时控制均在此处实现：
 * <ul>
 *   <li>并发控制：非并发任务通过静态 {@link #RUNNING_FLAG} 原子锁阻止重入；</li>
 *   <li>超时控制：将实际调用提交到独立线程，通过 {@code Future.get(timeout)} 中断。</li>
 * </ul>
 *
 * <p>执行结果<b>仅落库</b> {@code schedule_log}（供调度页面查询执行记录），
 * <b>不再向前端推送系统通知</b> —— 成功 / 失败 / 超时 / 跳过均不产生站内消息，
 * 避免高频定时任务刷屏。
 *
 * @author SAI
 */
@Slf4j
public class ScheduleJob implements Job {

    private static final Map<String, Boolean> RUNNING_FLAG = new ConcurrentHashMap<>();

    private static final ExecutorService INVOKE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quartz-invoke");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private transient TaskInvoker taskInvoker;

    @Autowired
    private transient ScheduleLogMapper logMapper;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();

        // Quartz 的 getLong/getInt 为强类型强转，JobDataMap 经持久化或合并后
        // Long 可能被存为 Integer（反之亦然），直接 getLong 会抛 ClassCastException。
        // 此处读原始对象做安全转换，避免类型不匹配导致任务整体崩溃。
        Long taskId = toLong(dataMap.get("taskId"));
        String taskName = dataMap.getString("taskName");
        String invokeTarget = dataMap.getString("invokeTarget");
        String groupName = dataMap.getString("groupName");
        // 任务类型：AGENT 分组记为 Agent 任务，其余记为通用任务
        String taskType = "AGENT".equalsIgnoreCase(groupName) ? "AGENT" : "COMMON";
        int concurrent = toInt(dataMap.get("concurrent"), 1);
        int timeout = toInt(dataMap.get("timeout"), 600);


        // ====== 非并发任务：原子锁检查 ======
        if (concurrent == 0) {
            if (RUNNING_FLAG.putIfAbsent(taskName, Boolean.TRUE) != null) {
                log.warn("[Quartz] 任务非并发且上次仍在执行，跳过本次触发 --> {}", taskName);
                writeLog(taskId, taskName, invokeTarget, taskType, false,
                        "SKIP: previous run still active", null, 0L);
                return;
            }
        }

        try {
            long start = System.currentTimeMillis();
            int timeoutSec = timeout <= 0 ? 600 : timeout;

            Object result;
            try {
                result = INVOKE_EXECUTOR.submit(() -> taskInvoker.invoke(invokeTarget))
                        .get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                long cost = System.currentTimeMillis() - start;
                log.error("[Quartz] 任务执行超时(>{}s) --> {}", timeoutSec, taskName);
                writeLog(taskId, taskName, invokeTarget, taskType, false,
                        "TIMEOUT after " + timeoutSec + "s", null, cost);
                return;
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("[Quartz] 任务执行失败 --> {} : {}", taskName, cause.getMessage(), cause);
                writeLog(taskId, taskName, invokeTarget, taskType, false,
                        cause.getMessage(), null, cost);
                return;
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[Quartz] 任务执行成功 --> {} (耗时 {}ms)", taskName, cost);
            writeLog(taskId, taskName, invokeTarget, taskType, true, null,
                    result == null ? "void" : String.valueOf(result), cost);
        } finally {
            if (concurrent == 0) {
                RUNNING_FLAG.remove(taskName);
            }
        }
    }

    /**
     * 落库执行日志（异步写，失败仅告警不阻塞）
     */
    private void writeLog(Long taskId, String taskName, String invokeTarget, String taskType,
                          boolean success, String errorMsg, String result, long costMs) {
        ScheduleLog logEntity = new ScheduleLog();
        logEntity.setTaskId(taskId);
        logEntity.setTaskName(taskName);
        logEntity.setInvokeTarget(invokeTarget);
        logEntity.setTaskType(taskType);
        logEntity.setSuccess(success ? 1 : 0);
        logEntity.setErrorMsg(errorMsg);
        logEntity.setResult(result);
        logEntity.setCostMs(costMs);

        logMapper.insert(logEntity)
                .doOnError(e -> log.warn("[Quartz] 写入日志失败 task={} --> {}", taskName, e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    /**
     * 安全转为 Long：兼容 null / Number 各子类型 / 数字字符串。
     */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全转为 int：兼容 null / Number 各子类型 / 数字字符串，失败时返回默认值。
     */
    private static int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
