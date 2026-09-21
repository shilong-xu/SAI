package xsl.sai.client.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 定时任务执行日志 视图对象
 *
 * @author SAI
 */
@Data
public class ScheduleLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long taskId;
    private String taskName;
    private String invokeTarget;
    /** 任务类型：COMMON=通用任务, AGENT=Agent 任务 */
    private String taskType;
    /** 是否成功 1/0 */
    private Integer success;
    private String errorMsg;
    private String result;
    /** 耗时（毫秒） */
    private Long costMs;
    private LocalDateTime createTime;
}
