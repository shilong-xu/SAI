package xsl.sai.client.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 邮件 视图对象（收 + 发统一记录）
 *
 * @author SAI
 */
@Data
public class ReceivedEmailVO implements Serializable {

    private Long id;
    private String userId;
    /** 邮件方向 0-接收 1-发送 */
    private Integer direction;
    private String messageId;
    private String fromAddr;
    private String fromName;
    private String toAddr;
    private String subject;
    private String content;
    private String summary;
    private Boolean hasAttachment;
    private Integer attachCount;
    private LocalDateTime receivedAt;
    private LocalDateTime createTime;
}
