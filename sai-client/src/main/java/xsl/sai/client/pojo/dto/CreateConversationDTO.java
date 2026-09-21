package xsl.sai.client.pojo.dto;

import lombok.Data;

/**
 * 创建会话入参
 *
 * @author SAI
 */
@Data
public class CreateConversationDTO {

    /** 标题（可空，默认“新对话”） */
    private String title;

    /** 模型（可空） */
    private String model;
}
