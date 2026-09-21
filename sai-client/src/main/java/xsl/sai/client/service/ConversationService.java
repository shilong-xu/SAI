package xsl.sai.client.service;

import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.ConversationVO;

import java.util.List;

/**
 * 会话列表服务
 *
 * @author SAI
 */
public interface ConversationService {

    /** 创建会话，返回新建的会话（含生成的 32 位 ID） */
    Mono<ConversationVO> create(String userId, String title, String model);

    /** 查询某用户全部会话（置顶优先、最近更新优先） */
    Mono<List<ConversationVO>> listByUser(String userId);

    /** 改名 */
    Mono<Void> rename(String conversationId, String title);

    /** 置顶 / 取消置顶 */
    Mono<Void> pin(String conversationId, Integer isPinned);

    /** 软删除（按用户归属校验） */
    Mono<Void> softDelete(String conversationId, String userId);

    /** 若是首条消息（消息数为 1），则用消息内容自动生成标题 */
    Mono<Void> autoTitleIfFirst(String conversationId, String message);
}
