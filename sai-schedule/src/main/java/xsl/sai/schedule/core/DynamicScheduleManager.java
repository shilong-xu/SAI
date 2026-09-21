package xsl.sai.schedule.core;

import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import xsl.sai.schedule.domain.ScheduleTask;
import xsl.sai.schedule.mapper.ScheduleTaskMapper;

/**
 * 动态定时任务管理器（Quartz 版）
 *
 * <p>职责：
 * <ol>
 *   <li>应用启动后从 {@code schedule_task} 表加载所有启用任务并注册到 Quartz；</li>
 *   <li>借助 Quartz {@link Scheduler} 管理 JobDetail + CronTrigger；</li>
 *   <li>提供运行时「注册/停止/刷新/立即触发」单条任务的能力；</li>
 *   <li>任务到期时由 {@link ScheduleJob} 执行并记录日志。</li>
 * </ol>
 *
 * @author SAI
 */
@Slf4j
@Component
public class DynamicScheduleManager implements InitializingBean, DisposableBean {

    private static final String JOB_GROUP = "SAI_SCHEDULE";

    private final ScheduleTaskMapper taskMapper;
    private final Scheduler scheduler;

    @Value("${sai.schedule.auto-start:true}")
    private boolean autoStart;

    public DynamicScheduleManager(ScheduleTaskMapper taskMapper, Scheduler scheduler) {
        this.taskMapper = taskMapper;
        this.scheduler = scheduler;
    }

    /**
     * 应用启动后加载并注册所有启用任务
     */
    @Override
    public void afterPropertiesSet() {
        if (!autoStart) {
            log.info("[Quartz] auto-start=false，跳过初始任务加载");
            return;
        }
        taskMapper.findEnabledTasks()
                .doOnNext(task -> {
                    try {
                        register(task);
                    } catch (SchedulerException e) {
                        log.error("[Quartz] 启动期注册失败 --> {}：{}", task.getName(), e.getMessage(), e);
                    }
                })
                .doOnComplete(() -> log.info("[Quartz] 初始任务加载完成"))
                .doOnError(e -> log.error("[Quartz] 初始任务加载失败 --> {}", e.getMessage(), e))
                .subscribe();
    }

    @Override
    public void destroy() {
        try {
            scheduler.shutdown(true);
            log.info("[Quartz] Scheduler 已关闭");
        } catch (SchedulerException e) {
            log.error("[Quartz] 关闭 Scheduler 失败 --> {}", e.getMessage(), e);
        }
    }

    // ======================== 公开 API ========================

    /**
     * 注册单条任务到 Quartz
     *
     * @throws SchedulerException 注册失败（调用方可 catch + log / 抛给上层处理）
     */
    public void register(ScheduleTask task) throws SchedulerException {
        JobKey jobKey = jobKey(task.getName());
        if (scheduler.checkExists(jobKey)) {
            log.info("[Quartz] 任务已存在，先删除再重建 --> {}", task.getName());
            scheduler.deleteJob(jobKey);
        }

        JobDetail jobDetail = JobBuilder.newJob(ScheduleJob.class)
                .withIdentity(jobKey)
                .usingJobData("taskId", task.getId())
                .usingJobData("taskName", task.getName())
                .usingJobData("invokeTarget", task.getInvokeTarget())
                .usingJobData("groupName", task.getGroupName() == null ? "DEFAULT" : task.getGroupName())
                .usingJobData("concurrent", task.getConcurrent() == null ? 1 : task.getConcurrent())
                .usingJobData("timeout", task.getTimeout() == null ? 600 : task.getTimeout())
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(task.getName()))
                .withSchedule(CronScheduleBuilder.cronSchedule(task.getCron()))
                .forJob(jobDetail)
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
        log.info("[Quartz] 已注册任务 --> {} (cron={}, 并发={})",
                task.getName(), task.getCron(), task.getConcurrent());
    }

    /**
     * 停止（删除）单条任务
     */
    public void stop(String taskName) {
        try {
            JobKey jobKey = jobKey(taskName);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("[Quartz] 已停止任务 --> {}", taskName);
            }
        } catch (SchedulerException e) {
            log.warn("[Quartz] 停止任务异常 --> {}：{}", taskName, e.getMessage());
        }
    }

    /**
     * 刷新任务：先停止再重新注册
     */
    public void refresh(ScheduleTask task) {
        stop(task.getName());
        try {
            register(task);
        } catch (SchedulerException e) {
            log.error("[Quartz] 刷新任务失败 --> {}：{}", task.getName(), e.getMessage(), e);
        }
    }

    /**
     * 立即触发一次（绕过 cron 调度，用于手动执行）
     */
    public void runOnce(ScheduleTask task) {
        try {
            JobKey jobKey = jobKey(task.getName());
            if (!scheduler.checkExists(jobKey)) {
                // 任务可能未在 Quartz 中注册（如停用状态），则临时注册并触发
                log.info("[Quartz] 手动触发，先临时注册 --> {}", task.getName());
                register(task);
            }
            scheduler.triggerJob(jobKey);
            log.info("[Quartz] 手动触发任务 --> {}", task.getName());
        } catch (SchedulerException e) {
            log.error("[Quartz] 手动触发失败 --> {}：{}", task.getName(), e.getMessage(), e);
        }
    }

    // ======================== 内部工具 ========================

    private static JobKey jobKey(String name) {
        return JobKey.jobKey(name, JOB_GROUP);
    }

    private static TriggerKey triggerKey(String name) {
        return TriggerKey.triggerKey(name, JOB_GROUP);
    }
}
