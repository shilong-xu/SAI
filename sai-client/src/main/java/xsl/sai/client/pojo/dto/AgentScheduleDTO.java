package xsl.sai.client.pojo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Agent 定时任务 DTO
 * <p>自动将 message 转为 invoke_target = {@code scheduleTask.agentChat('message')}
 *
 * @author SAI
 */
@Data
public class AgentScheduleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务ID（更新时必填） */
    private Long id;

    /** 任务名 */
    private String name;

    /** Cron 表达式 */
    private String cron;

    /** Agent 对话消息（即传给 agent 的 prompt） */
    private String message;

    /** 描述说明 */
    private String description;

    /** 分组（默认 AGENT） */
    private String groupName;
}
