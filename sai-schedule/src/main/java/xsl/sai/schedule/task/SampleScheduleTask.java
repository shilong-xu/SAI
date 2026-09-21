package xsl.sai.schedule.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 示例定时任务 Bean
 *
 * <p>可被 {@code schedule_task.invoke_target} 通过 {@code sampleScheduleTask.heartbeat()} 调用。
 * 删除本示例或替换为真实业务任务即可。</p>
 *
 * @author SAI
 */
@Slf4j
@Component
public class SampleScheduleTask {

    /**
     * 心跳示例：打印一行日志并返回当前时间戳
     */
    public String heartbeat() {
        String msg = "schedule heartbeat @ " + System.currentTimeMillis();
        log.info("[示例任务] {}", msg);
        return msg;
    }

    /**
     * 无参示例：清理类任务的占位实现
     */
    public void cleanup() {
        log.info("[示例任务] 执行清理逻辑（占位）");
    }
}
