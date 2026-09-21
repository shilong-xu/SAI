package xsl.sai.client.service;

import io.agentscope.core.message.Source;
import reactor.core.publisher.Flux;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.framework.result.Result;

/**
 * &#064;DATE: 2026/6/15 19:42
 * &#064;AUTHOR: XSL
 *
 */
public interface AgentAIService {

    /**
     * 统一对话入口：按 {@link ChatDTO} 中的模式状态（saaMode / loopMode）由策略工厂分派到
     * 对应的对话模式策略（普通对话 / 思考追踪 / SAA 编排），返回 SSE 流。
     *
     * @param chatDTO 对话参数
     * @return 每一步的 {@code Result} 流，末尾附带 message="END" 的结束标记
     */
    Flux<Result> chat(ChatDTO chatDTO);

    /**
     * 统一对话入口（带文件）：文件源（Source）由 Controller 解析后透传，仅普通对话模式会使用。
     *
     * @param chatDTO  对话参数
     * @param source   文件源(Base64Source或URLSource)
     * @param fileName 上传文件名（透传给底层，用于让模型识别文件类型/名称）
     * @return 每一步的 {@code Result} 流，末尾附带 message="END" 的结束标记
     */
    Flux<Result> chat(ChatDTO chatDTO, Source source, String fileName);

}
