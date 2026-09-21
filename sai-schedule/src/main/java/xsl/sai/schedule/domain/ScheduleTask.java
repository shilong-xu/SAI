package xsl.sai.schedule.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务定义实体 —— 对应 {@code schedule_task} 表
 *
 * <p>字段设计遵循 SQL 约定：
 * <ul>
 *   <li>{@code concurrent} / {@code status} 用 Integer（非 Boolean），
 *       因为 MariaDB 会把 tinyint(1) 当 Boolean 返回导致 R2DBC 转换失败，故用 tinyint(4)。</li>
 *   <li>{@code invokeTarget} 形如 {@code beanName.methodName}，运行时反射调用。</li>
 * </ul>
 *
 * @author SAI
 */
@Data
public class ScheduleTask {

    /** 主键 */
    private Long id;

    /** 任务名（唯一，作为调度器注册 key） */
    private String name;

    /** 任务分组 */
    private String groupName;

    /** 任务描述 */
    private String description;

    /** 调用目标 beanName.methodName(参数...) */
    private String invokeTarget;

    /** Cron 表达式 */
    private String cron;

    /** 单次执行超时（秒） */
    private Integer timeout;

    /** 是否允许并发（0否 1是） */
    private Integer concurrent;

    /** 状态 1-启用 0-停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 修改时间 */
    private LocalDateTime updateTime;

    /** 备注 */
    private String remark;

    /** 是否删除（0否 1是） */
    private Integer isDelete;

    /** 是否启用 */
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    /** 是否允许并发执行 */
    public boolean isConcurrent() {
        return concurrent == null || concurrent == 1;
    }
}
