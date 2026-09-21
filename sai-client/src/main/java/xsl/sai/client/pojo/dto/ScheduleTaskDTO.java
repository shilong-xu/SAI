package xsl.sai.client.pojo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 定时任务 创建/更新 DTO
 *
 * @author SAI
 */
@Data
public class ScheduleTaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务ID（更新时必填，创建时忽略） */
    private Long id;

    /** 任务名（唯一） */
    private String name;

    /** 任务分组 */
    private String groupName;

    /** 任务描述 */
    private String description;

    /** Cron 表达式 */
    private String cron;

    /** 调用目标，形如 beanName.methodName(arg1, arg2) */
    private String invokeTarget;

    /** 是否并发（1 并发，0 非并发） */
    private Integer concurrent;

    /** 执行超时秒数 */
    private Integer timeout;

    /** 状态（1 启用，0 停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
