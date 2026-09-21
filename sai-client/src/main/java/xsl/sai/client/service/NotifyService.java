package xsl.sai.client.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.NotifyMessageVO;

import java.util.Map;

/**
 * 站内消息服务
 *
 * @author SAI
 */
public interface NotifyService {

    /**
     * 推送消息：落库 + 经 SSE 实时推送给该用户（不在线则下次拉取列表时可见）
     *
     * @param userId  接收用户
     * @param type    消息类型 info / success / warn / error
     * @param title   标题
     * @param content 内容
     * @param link    点击跳转（前端视图名，可空）
     * @return 持久化后的消息
     */
    Mono<NotifyMessageVO> push(String userId, String type, String title, String content, String link);

    /** 最近消息列表（未读优先，再按时间倒序） */
    Flux<NotifyMessageVO> list(String userId, int limit);

    /** 未读数量 */
    Mono<Long> unreadCount(String userId);

    /** 单条已读，返回是否命中（false = 消息不存在 / 不属于当前用户 / 已读过） */
    Mono<Boolean> read(String userId, Long id);

    /** 全部已读，返回更新条数 */
    Mono<Integer> readAll(String userId);

    /**
     * 增量同步：一次拿到「当前未读数 + 自 afterId 之后到达的新消息」。
     * <p>前端在页面刷新 / SSE 重连后调用，用 id 游标补偿断线窗口内错过的实时推送
     * （消息始终先落库，故不丢；missed 新到优先、最多 20 条）。
     *
     * @param userId  接收用户
     * @param afterId 本地已见的最大消息 id（首次为 0）
     * @return count=未读数，missed=该 id 之后的消息列表（最新在前）
     */
    Mono<Map<String, Object>> sync(String userId, long afterId);
}
