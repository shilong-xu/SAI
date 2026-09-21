package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * &#064;DATE: 2026/6/15 19:42
 * &#064;AUTHOR: XSL
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatVO {

    /**
     * 回答
     */
    private String reply;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 是否「模型未返回内容的兜底占位」：为 true 时前端仅展示、不计入对话历史（后端亦不落库）。
     * 用于区分「用户主动停止」与「模型空输出」，避免空输出被误显示为「（已停止生成）」。
     */
    private Boolean emptyReply;


}
