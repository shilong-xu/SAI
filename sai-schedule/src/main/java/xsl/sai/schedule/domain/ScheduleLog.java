package xsl.sai.schedule.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行日志实体 —— 对应 {@code schedule_log} 表
 *
 * @author SAI
 */
@Data
public class ScheduleLog {

    /** 主键 */
    private Long id;

    /** 任务ID */
    private Long taskId;

    /** 任务名（冗余） */
    private String taskName;

    /** 本次调用的目标表达式 */
    private String invokeTarget;

    /** 任务类型：COMMON=通用任务, AGENT=Agent 任务 */
    private String taskType;

    /** 方法返回值 */
    private String result;

    /** 是否成功（0否 1是） */
    private Integer success;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 修改时间 */
    private LocalDateTime updateTime;

    /** 备注 */
    private String remark;

    /** 是否删除（0否 1是） */
    private Integer isDelete;
}
