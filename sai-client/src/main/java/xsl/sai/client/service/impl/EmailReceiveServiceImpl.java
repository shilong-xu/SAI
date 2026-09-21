package xsl.sai.client.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.agent.handler.SemanticRefineHandler;
import xsl.sai.client.domain.ReceivedEmailEntity;
import xsl.sai.client.mail.MailAgentDispatcher;
import xsl.sai.client.mapper.ReceivedEmailMapper;
import xsl.sai.client.pojo.vo.ReceivedEmailVO;
import xsl.sai.client.service.EmailReceiveService;
import xsl.sai.framework.client.MailClient;
import xsl.sai.framework.client.MailReceiver;
import xsl.sai.framework.client.RedisClient;

import java.time.LocalDateTime;

/**
 * 邮件服务实现（收 + 发统一记录）
 *
 * <p>接收：拉取 → 按 Message-ID 去重 → 模型提炼描述 → 入库 →（可选）提交给 Agent 处理。
 * <p>发送：模型提炼描述 → 入库，<b>不</b>发布事件总线（发送链路不走 Agent，避免「自动回复 → 入库 → 又被当新邮件处理」的循环）。
 *
 * <p><b>接收后是否交给 Agent</b>：由 {@code spring.mail.receive.dispatch-to-agent} 控制（默认 false）。
 * 开启时，每封新入库的接收邮件会交给 {@link MailAgentDispatcher} 异步提交给 Agent，
 * 由其按邮件分类规则判定是否需要登记为待办事项；关闭时只入库、不做任何模型调用。
 *
 * <p><b>摘要（{@code summary}）</b>：收发两条链路统一复用
 * {@link SemanticRefineHandler#refine(String, int)} 用模型把邮件提炼为精简描述，
 * 共用同一套提示词、风格一致；主题与正文皆空时为 {@code null}。
 * <p>两条链路都不再做向量化；发送链路不发布事件总线；接收链路的 Agent 处理由上述开关控制。
 *
 * <p><b>Bean 名必须显式指定为 {@code emailReceiveService}</b>：本类名为
 * {@code EmailReceiveServiceImpl}，Spring 默认生成的 Bean 名是 {@code emailReceiveServiceImpl}，
 * 会让调度表里的 {@code invoke_target=emailReceiveService.fetchAndStore()}
 * （以及 {@code sai.schedule.allowed-beans} 白名单）解析不到 Bean 而持续报
 * {@code No bean named 'emailReceiveService' available}。
 *
 * @author SAI
 */
@Service("emailReceiveService")
@Slf4j
public class EmailReceiveServiceImpl implements EmailReceiveService {

    /**
     * 抓取收件箱的分布式锁 key：多实例部署、或定时任务与手动抓取同时触发时，
     * 保证同一时刻只有一个实例真正在拉取收件箱。
     */
    private static final String FETCH_LOCK_KEY = "sai:lock:email:fetch";

    private final ReceivedEmailMapper mapper;
    private final MailReceiver mailReceiver;
    private final MailClient mailClient;
    private final SemanticRefineHandler refineHandler;
    /** 抓取入库后把新邮件提交给 Agent 处理的入口（是否真的提交由配置项决定，见该类注释） */
    private final MailAgentDispatcher agentDispatcher;

    public EmailReceiveServiceImpl(ReceivedEmailMapper mapper,
                                  MailReceiver mailReceiver,
                                  MailClient mailClient,
                                  SemanticRefineHandler refineHandler,
                                  MailAgentDispatcher agentDispatcher) {
        this.mapper = mapper;
        this.mailReceiver = mailReceiver;
        this.mailClient = mailClient;
        this.refineHandler = refineHandler;
        this.agentDispatcher = agentDispatcher;
    }

    /**
     * 抓取收件箱新邮件并入库（定时任务 + 手动「立即抓取」的唯一入口）。
     *
     * <p><b>全程持 Redis 分布式锁</b>（key = {@value #FETCH_LOCK_KEY}）：定时任务与手动抓取、
     * 或多实例部署同时触发时，只有抢到锁的一方真正拉取收件箱，其余直接返回 0（本轮不抓取）。
     * 抢不到就跳过而不排队等待——抓取是幂等的周期行为，正在跑的那一轮会把同一批邮件处理掉，
     * 等待只会让请求堆积。
     *
     * <p><b>为什么用同步锁 + {@code .block()}</b>：Redisson 的响应式锁绑定「加锁那一刻的线程」，
     * Reactor 中解锁往往已在别的线程，会抛 {@code IllegalMonitorStateException}
     * （详见 {@link RedisClient#syncLock(String)}）。因此这里把「加锁 → 抓取 → 解锁」
     * 整体放到一个弹性线程池线程内串行完成：阻塞的是后台线程，事件循环不受影响，
     * 与 {@code TaskInvoker} 承接 Mono 返回值的方式一致。
     *
     * <p><b>Redis 不可用时降级</b>：拿不到锁对象、或加锁时抛异常（Redis 抖动）都不阻断邮件抓取，
     * 记 WARN 后无锁执行——重复拉取由 {@code received_email.message_id} 唯一索引兜底。
     */
    @Override
    public Mono<Long> fetchAndStore() {
        return Mono.fromCallable(this::fetchAndStoreLocked)
                // 锁是同步阻塞的，放到弹性线程池执行，不占用事件循环
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在同一个线程内完成「加锁 → 抓取 → 解锁」，返回本轮新入库的邮件数。
     *
     * <p>只应由 {@link #fetchAndStore()} 调用（由它负责切到弹性线程池）。
     */
    private Long fetchAndStoreLocked() {
        final RLock lock;
        try {
            lock = RedisClient.syncLock(FETCH_LOCK_KEY);
        } catch (Exception e) {
            log.warn("[Email] 获取分布式锁对象失败（Redis 不可用？），本次抓取降级为无锁执行 --> {}", e.getMessage());
            return runFetch();
        }
        if (lock == null) {
            log.warn("[Email] Redis 不可用，本次抓取降级为无锁执行（多实例并发时可能出现重复拉取）");
            return runFetch();
        }

        final boolean acquired;
        try {
            // tryLock()：立即尝试不等待，并由 Redisson 看门狗自动续期
            // （抓取耗时不可预知，写死 leaseTime 可能在抓取未结束时就过期，导致并发进入）
            acquired = lock.tryLock();
        } catch (Exception e) {
            log.warn("[Email] 加锁异常（Redis 抖动？），本次抓取降级为无锁执行 --> {}", e.getMessage());
            return runFetch();
        }
        if (!acquired) {
            log.info("[Email] 已有实例正在抓取邮件，本次跳过（lock={}）", FETCH_LOCK_KEY);
            return 0L;
        }

        try {
            return runFetch();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    // 解锁失败不影响本次抓取结果；锁会在看门狗停止续期后自然过期
                    log.warn("[Email] 释放分布式锁失败，将由看门狗自动过期 --> {}", e.getMessage());
                }
            }
        }
    }

    /** 无锁执行抓取（供加锁路径与降级路径共用）；{@code count()} 保证有值，这里再兜一次 null → 0 */
    private Long runFetch() {
        return doFetchAndStore().blockOptional().orElse(0L);
    }

    /** 抓取主体（不含锁）：拉取 → 按 Message-ID 去重 → 提炼摘要入库 →（按配置）提交给 Agent */
    private Mono<Long> doFetchAndStore() {
        return mailReceiver.fetchRecent()
                .flatMapMany(Flux::fromIterable)
                // 去重：已存在相同 Message-ID 的邮件跳过
                .filterWhen(m -> exists(m.messageId()).map(ex -> !ex))
                .map(this::toEntity)
                .flatMap(this::saveWithSummary, 4)
                // 入库后按配置决定是否提交给 Agent 处理：异步 fire-and-forget，
                // 不阻塞抓取返回（Agent 处理要调模型，可能几十秒）
                .doOnNext(agentDispatcher::dispatchAsync)
                .count()
                .doOnSuccess(c -> log.info("[Email] 本轮抓取并入库新邮件 {} 封{}", c,
                        agentDispatcher.isEnabled() ? "（已按配置提交给 Agent 处理）" : ""))
                .doOnError(e -> log.error("[Email] 抓取邮件失败 --> {}", e.getMessage(), e));
    }

    @Override
    public Mono<Void> saveSent(SentMail mail) {
        ReceivedEmailEntity e = new ReceivedEmailEntity();
        e.setUserId(mail.userId());
        e.setDirection(DIRECTION_SEND);
        // 收发共用同一个邮箱，发件人即当前邮箱配置的账号
        e.setFromAddr(mailClient.getFromAddress());
        e.setFromName(mailClient.getFromName());
        e.setToAddr(mail.toAddr());
        e.setSubject(mail.subject());
        e.setContent(mail.content());
        e.setHasAttachment(false);
        e.setAttachCount(0);
        e.setReceivedAt(LocalDateTime.now());
        // 发送邮件入库留痕，不再做 Agent 分析 / 向量化（与接收一致，已取消 Agent 处理）
        // summary 由模型提炼精简描述（与接收共用 refine 的同一套提示词，风格一致）
        return saveWithSummary(e)
                .doOnNext(saved -> log.info("[Email] 已记录发送邮件 id={}, to={}", saved.getId(), saved.getToAddr()))
                .then()
                .onErrorResume(ex -> {
                    log.warn("[Email] 发送邮件入库失败（不影响发送结果）to={} --> {}", mail.toAddr(), ex.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Flux<ReceivedEmailVO> list(String keyword, Integer direction, int page, int size) {
        int limit = size <= 0 ? 50 : size;
        int offset = (page <= 0 ? 0 : page - 1) * limit;
        return mapper.page(likePattern(keyword), direction, limit, offset).map(this::toVO);
    }

    @Override
    public Mono<Long> count(String keyword, Integer direction) {
        return mapper.countByKw(likePattern(keyword), direction).defaultIfEmpty(0L);
    }

    @Override
    public Mono<ReceivedEmailVO> getById(Long id) {
        return mapper.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("邮件不存在")))
                .map(this::toVO);
    }

    @Override
    public Mono<Void> delete(Long id) {
        return mapper.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("邮件不存在")))
                .flatMap(e -> {
                    e.setIsDelete(true);
                    return mapper.save(e);
                })
                .then();
    }

    // ======================== 内部辅助 ========================

    /** 关键词转 LIKE 模式（空值表示不按关键词过滤） */
    private String likePattern(String keyword) {
        return StrUtil.isBlank(keyword) ? null : "%" + keyword + "%";
    }

    /** 判断某 Message-ID 的邮件是否已存在（用于去重） */
    private Mono<Boolean> exists(String messageId) {
        if (StrUtil.isBlank(messageId)) {
            // 无 Message-ID 时不参与去重，视为不存在（每次都会重新入库）
            return Mono.just(false);
        }
        return mapper.findIdByMessageId(messageId)
                .map(id -> true)
                .switchIfEmpty(Mono.just(false));
    }

    /** 将拉取到的邮件映射为实体（接收方向，新记录待入库） */
    private ReceivedEmailEntity toEntity(MailReceiver.ReceivedMail m) {
        ReceivedEmailEntity e = new ReceivedEmailEntity();
        e.setDirection(DIRECTION_RECEIVE);
        e.setMessageId(m.messageId());
        e.setFromAddr(m.fromAddr());
        e.setFromName(m.fromName());
        e.setToAddr(m.toAddr());
        e.setSubject(m.subject());
        e.setContent(m.content());
        e.setHasAttachment(m.hasAttachment());
        e.setAttachCount(m.attachCount());
        e.setReceivedAt(m.sentDate());
        return e;
    }

    /**
     * 入库（收发共用）：先用模型把邮件提炼为精简描述写入 {@code summary}，再落库。
     *
     * <p>提炼直接复用 {@link SemanticRefineHandler#refine(String, int)}——收发两条链路走同一套
     * 提示词与调用逻辑，摘要风格天然一致，不再单独维护邮件专用提示词。
     * 第二参传 {@code 0} 表示<b>强制提炼</b>：阈值 0 时只要文本非空就一定走模型，
     * 不会「原文够短就原样返回」，保证 summary 是模型提炼结果而非原文拷贝。
     *
     * <p>提炼失败时 {@code refine} 回退为原文截断片段，此处仍照常落库（邮件不丢）；
     * 仅当主题与正文皆空时存 {@code null}（列表按「未摘要」统计）。
     */
    private Mono<ReceivedEmailEntity> saveWithSummary(ReceivedEmailEntity e) {
        String text = (e.getSubject() == null ? "" : e.getSubject())
                + "\n" + (e.getContent() == null ? "" : e.getContent());
        return refineHandler.refine(text, 0)
                .map(s -> {
                    // summary 列 varchar(1024)，模型不守规矩时截断兜底，避免写库失败
                    String v = StrUtil.isBlank(s) ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
                    e.setSummary(v);
                    return e;
                })
                .flatMap(mapper::save);
    }

    private ReceivedEmailVO toVO(ReceivedEmailEntity e) {
        ReceivedEmailVO vo = new ReceivedEmailVO();
        BeanUtils.copyProperties(e, vo);
        return vo;
    }
}
