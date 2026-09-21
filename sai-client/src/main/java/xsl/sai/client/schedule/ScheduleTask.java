package xsl.sai.client.schedule;

import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.agent.AgentAI;
import xsl.sai.agent.domain.AgentMemoryEntity;
import xsl.sai.agent.service.AgentMemoryService;
import xsl.sai.framework.client.EmbeddingClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务调度执行入口
 * &#064;DATE: 2026/8/4 16:45
 * &#064;AUTHOR: XSL
 */
@Slf4j
@Component
public class ScheduleTask {

    private static final String SCHEDULE_USER_ID = "21232f297a57a5a743894a0e4a801fc3";

    /**
     * 定时任务无人值守模式标记：前缀注入到 message，触发 AGENTS.md「十、定时任务执行模式」约定。
     * 含义：后台无人工确认/无交互，由 agent 自主完成多步编排并以执行摘要收尾。
     */
    private static final String SCHEDULE_MODE_TAG = "[定时任务-无人值守模式] ";

    /** agent 执行整体超时（兜底，防止任务无限挂起；真实超时由调度侧 timeout 控制）。 */
    private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 单次补全最多处理的缺失向量条数（分批滚动，避免一次拉回/向量化过多记录）。
     * 与 {@code MysqlVectorLongTermMemory} 沉淀链路的节奏保持一致。
     */
    private static final int REPAIR_BATCH = 50;

    /**
     * 向量化前的安全截断字符数：与 {@code MysqlVectorLongTermMemory#SAFE_EMBED_CHARS} 对齐，
     * 防止超长正文直接喂给 bge-m3 触发 Ollama 上下文超限（HTTP 500）。
     */
    private static final int SAFE_EMBED_CHARS = 4000;

    @Autowired
    private AgentAI agentAI;

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private EmbeddingClient embeddingClient;

    /**
     * agent 对话入口（供定时任务调度反射调用）
     * <p>
     * 本方法内部<strong>不再调用任何阻塞式 API</strong>（无 {@code .block()}），返回 {@link Mono<String>}。
     * 结果的汇聚由 {@code TaskInvoker} 在独立的后台调度线程上完成（非响应式事件循环线程），
     * 因此不会阻塞在线的响应式/流式链路。
     * 调用前会注入「无人值守模式」标记，使主 Agent 切换到后台自动编排行为（见 AGENTS.md 第十条）。
     * invoke_target 写法示例：{@code scheduleTask.agentChat('每天下午5点检查服务器状态')}
     *
     * @param message 对话内容（即定时任务要 agent 完成的指令，可含多步编排意图，如"调用接口并把结果发邮件"）
     * @return 执行结果摘要（以 {@link Mono} 承载，订阅后产出字符串）
     */
    public Mono<String> agentChat(String message) {
        String sessionId = IdUtil.fastSimpleUUID();
        String taggedMessage = SCHEDULE_MODE_TAG + (message == null ? "" : message);
        log.info("[调度·Agent] 开始执行, sessionId={}, message={}", sessionId, message);

        return agentAI.process(SCHEDULE_USER_ID, sessionId, taggedMessage)
                .collectList()
                .map(deltas -> {
                    String fullReply = (deltas == null) ? "(无回复)" : String.join("", deltas);
                    log.info("[调度·Agent] 执行完成, sessionId={}, deltaCount={}",
                            sessionId, deltas == null ? 0 : deltas.size());
                    return fullReply.length() > 200 ? fullReply.substring(0, 200) + "..." : fullReply;
                })
                .timeout(AGENT_TIMEOUT, Mono.defer(() -> {
                    log.error("[调度·Agent] 执行超时(>{}), sessionId={}", AGENT_TIMEOUT, sessionId);
                    return Mono.just("执行失败: 超时(" + AGENT_TIMEOUT.toMinutes() + "分钟)");
                }))
                .onErrorResume(e -> {
                    log.error("[调度·Agent] 执行异常, sessionId={}, message={}", sessionId, message, e);
                    return Mono.just("执行失败: " + e.getMessage());
                });
    }

    /**
     * 补全长期记忆缺失的向量字段（embedding_vec 为 NULL 的记忆）。
     *
     * <p><b>背景</b>：{@code AgentMemoryServiceImpl#saveMemory} 在向量化模型偶发报错时，
     * 会「只落文本、不落向量」以保证记忆不丢（见其 else 分支），这些记忆因此暂不可被语义检索召回。
     * 本定时任务周期性扫描缺失向量记录，用相同的 bge-m3 模型重新向量化并回填 {@code embedding_vec}，
     * 使其恢复语义检索能力。
     *
     * <p><b>一致性</b>：超长正文先截断到 {@link #SAFE_EMBED_CHARS} 字符再向量化，
     * 与沉淀链路 {@code MysqlVectorLongTermMemory} 的兜底截断逻辑一致，避免触发 Ollama 上下文超限。
     * （若沉淀时启用了语义精简，此处用的是原文截断向量，二者在「原始向量缺失」场景下差异可忽略。）
     *
     * <p><b>调用目标写法</b>（schedule_task.invoke_target）：
     * {@code scheduleTask.repairMemoryEmbedding()}
     *
     * @return 执行摘要（供 schedule_log 落库），无返回值时上层记为 "void"
     */
    public Mono<String> repairMemoryEmbedding() {
        if (!embeddingClient.isEnabled()) {
            log.info("[调度·记忆补全] Embedding 客户端未启用，跳过本次补全");
            return Mono.just("跳过：Embedding 客户端未启用");
        }
        return agentMemoryService.findMissingEmbedding(REPAIR_BATCH)
                .collectList()
                .flatMap(missing -> {
                    if (missing == null || missing.isEmpty()) {
                        log.info("[调度·记忆补全] 无缺失向量的长期记忆");
                        return Mono.just("长期记忆向量补全：无缺失记录");
                    }
                    // 组装待向量化文本（跳过空文本），原文超长先截断
                    List<Long> ids = new ArrayList<>(missing.size());
                    List<String> texts = new ArrayList<>(missing.size());
                    for (AgentMemoryEntity m : missing) {
                        String c = m.getContent();
                        if (c == null || c.isBlank()) {
                            continue;
                        }
                        ids.add(m.getId());
                        texts.add(safeClamp(c, SAFE_EMBED_CHARS));
                    }
                    if (ids.isEmpty()) {
                        return Mono.just("长期记忆向量补全：待处理记录均无有效文本，跳过");
                    }
                    log.info("[调度·记忆补全] 发现 {} 条缺失向量，开始批量向量化", ids.size());
                    return Mono.fromCallable(() -> embeddingClient.embed(texts))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(vecs -> {
                                if (vecs == null || vecs.size() != ids.size()) {
                                    return Flux.error(new IllegalStateException(
                                            "向量数量不匹配：期望 " + ids.size()
                                                    + "，实际 " + (vecs == null ? 0 : vecs.size())));
                                }
                                // 逐条回填（concatMap 串行，避免瞬时打满 DB / 向量服务）
                                return Flux.range(0, ids.size())
                                        .concatMap(i -> {
                                            float[] v = vecs.get(i);
                                            if (v == null || v.length == 0) {
                                                return Mono.empty();
                                            }
                                            return agentMemoryService.fillEmbedding(ids.get(i), v).then();
                                        });
                            })
                            .then(Mono.defer(() -> agentMemoryService.countMissingEmbedding()
                                    .map(remain -> String.format(
                                            "长期记忆向量补全：本次处理 %d 条，剩余缺失 %d 条", ids.size(), remain))))
                            .onErrorResume(e -> {
                                log.error("[调度·记忆补全] 批量向量化失败: {}", e.getMessage(), e);
                                return Mono.just("长期记忆向量补全失败: " + e.getMessage());
                            });
                });
    }

    /** 截断到最大长度（<=0 表示不限制），与沉淀链路 safeClamp 同义 */
    private static String safeClamp(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

}
