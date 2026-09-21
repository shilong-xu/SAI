package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话列表展示对象
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {

    private String id;
    private String userId;
    private String title;
    private String model;
    private Integer isPinned;
    private String remark;
    private String createTime;
    private String updateTime;
    /** 消息条数（前端可展示预览用） */
    private Long messageCount;
}
