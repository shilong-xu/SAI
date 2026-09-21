package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息记录展示对象
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {

    private Long id;
    private String conversationId;
    private String role;
    private String content;
    private Integer tokens;
    private String createTime;
}
