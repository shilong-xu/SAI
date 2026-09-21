package xsl.sai.client.pojo.dto;

import lombok.Data;

/**
 * 更新会话入参（改名 / 置顶）
 *
 * @author SAI
 */
@Data
public class UpdateConversationDTO {

    /** 会话ID */
    private String id;

    /** 新标题（改名时传） */
    private String title;

    /** 置顶状态 0/1（置顶时传） */
    private Integer isPinned;
}
