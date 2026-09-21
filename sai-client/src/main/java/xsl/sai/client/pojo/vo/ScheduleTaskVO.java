package xsl.sai.client.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 定时任务 视图对象
 *
 * @author SAI
 */
@Data
public class ScheduleTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String groupName;
    private String description;
    private String cron;
    private String invokeTarget;
    /** 是否并发 */
    private Integer concurrent;
    /** 执行超时秒 */
    private Integer timeout;
    /** 状态：1 启用 0 停用 */
    private Integer status;
    /** 备注 */
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDelete;
}
