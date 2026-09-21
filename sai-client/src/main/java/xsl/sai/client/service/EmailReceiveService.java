package xsl.sai.client.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.ReceivedEmailVO;

/**
 * 邮件服务（收 + 发统一记录）
 *
 * <p>表中用 {@link #DIRECTION_RECEIVE} / {@link #DIRECTION_SEND} 区分方向，两类邮件的处理方式不同：
 * <ul>
 *   <li><b>接收</b>：由定时任务从收件箱单向入库（{@link #fetchAndStore()}），入库后不再向量化、不发布事件总线；
 *       <b>是否交给 Agent 处理由配置项 {@code spring.mail.receive.dispatch-to-agent} 决定</b>（默认关闭）；</li>
 *   <li><b>发送</b>：Agent 调用 {@code send_mail} 成功后由事件监听转入库留痕（{@link #saveSent}），仅作记录、不走 Agent，
 *       不存在「自动回复 → 入库 → 又被当新邮件处理」的循环。</li>
 * </ul>
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #fetchAndStore()}：被定时任务（每 5 分钟）调用，拉取收件箱新邮件、去重、入库；开关开启时把新邮件提交给 Agent；</li>
 *   <li>{@link #saveSent}：记录一封已成功发送的邮件；</li>
 *   <li>查询与管理：列表 / 计数 / 详情 / 软删除。</li>
 * </ul>
 *
 * @author SAI
 */
public interface EmailReceiveService {

    /** 邮件方向：接收 */
    int DIRECTION_RECEIVE = 0;
    /** 邮件方向：发送 */
    int DIRECTION_SEND = 1;

    /**
     * 拉取并入库新邮件（定时任务 / 前端「抓取邮件」入口）。返回本次新入库的邮件数量。
     *
     * <p>每封新邮件入库后，若 {@code spring.mail.receive.dispatch-to-agent=true}，
     * 会异步提交给 Agent 处理（判定邮件类型 / 是否需要登记为待办），不阻塞本方法的返回。
     *
     * <p><b>并发语义</b>：实现类全程持 Redis 分布式锁（key {@code sai:lock:email:fetch}）——
     * 定时任务与手动抓取、或多实例部署同时触发时，只有抢到锁的一方真正拉取收件箱，
     * 其余<b>立即返回 {@code 0}</b>（含义是「本轮未抓取」，而非「收件箱没有新邮件」）。
     */
    Mono<Long> fetchAndStore();

    /**
     * 记录一封「已发送」的邮件（发送成功后入库留痕，{@code direction=1}）。
     *
     * <p>不会发布事件总线，因此不会被 Agent 二次处理；发件人取自当前邮箱配置
     * （{@code spring.mail.username} / {@code spring.mail.from}）。
     *
     * @param mail 已发送邮件（收件人 / 主题 / 正文）
     */
    Mono<Void> saveSent(SentMail mail);

    /**
     * 列表（分页 + 关键词模糊：主题 / 正文 / 发件人）
     *
     * @param keyword   关键词（可空）
     * @param direction 邮件方向 0-接收 1-发送（为空表示不限方向）
     */
    Flux<ReceivedEmailVO> list(String keyword, Integer direction, int page, int size);

    /** 列表计数（direction 为空表示不限方向） */
    Mono<Long> count(String keyword, Integer direction);

    /** 详情 */
    Mono<ReceivedEmailVO> getById(Long id);

    /** 逻辑删除 */
    Mono<Void> delete(Long id);

    /**
     * 已发送邮件（发送成功后入库的传输对象）
     *
     * @param userId  触发发送的会话用户（后台场景为系统身份，可为空）
     * @param toAddr  收件人（多个以逗号分隔）
     * @param subject 主题
     * @param content 正文原文
     */
    record SentMail(String userId, String toAddr, String subject, String content) {
    }
}
