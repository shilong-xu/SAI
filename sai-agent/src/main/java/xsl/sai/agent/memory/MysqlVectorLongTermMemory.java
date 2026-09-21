package xsl.sai.agent.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.agent.domain.AgentMemoryEntity;
import xsl.sai.agent.handler.SemanticRefineHandler;
import xsl.sai.agent.service.AgentMemoryService;
import xsl.sai.framework.client.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MySQL + 向量检索的长期记忆实现 —— 项目自研后端，替代框架的 workspace 文件记忆。
 *
 * <p><b>为什么不实现官方 {@code io.agentscope.core.memory.LongTermMemory}</b>：
 * 该接口自 2.0.0 起同样被 {@code @Deprecated(forRemoval=true)} 标记（与 {@code LongTermMemoryTools} 同期），
 * 且官方 2.0 的长期记忆方案已整体改为 workspace 文件（{@code MEMORY.md} + {@code memory/*.md}）。
 * 本项目要的是跨副本、可语义检索的 MySQL 向量库，属自定义后端，因此不再挂靠该接口，
 * 直接作为普通 Bean 由 {@link LongTermMemoryMiddleware} 调用。
 *
 * <p><b>对外方法</b>：
 * <pre>
 *   Mono&lt;String&gt; retrieve(Msg, String userId)            // 召回，返回值即注入 system prompt 的文本
 *   Mono&lt;Void&gt;   record(List&lt;Msg&gt;, String userId, String sessionId)  // 沉淀
 * </pre>
 *
 * <p><b>两个关键实现决策</b>：
 * <ol>
 *   <li><b>userId 显式传参</b>：不走 Reactor Context，由调用方（Middleware）从 {@code RuntimeContext} 取好后传入，
 *       多用户场景不会串号；取不到时回退 {@code defaultUserId}。</li>
 *   <li><b>record 只扫尾部</b>：调用方给的是「本轮输入 + 最终回复」，历史消息本就在库里，
 *       这里只扫尾部若干条 + 依赖向量去重，避免重复入库。</li>
 * </ol>
 *
 * <p><b>入库粒度可切换</b>：
 * <ul>
 *   <li>{@code RAW} —— 原始消息直接向量化，零 LLM 成本，但含噪音（默认）；</li>
 *   <li>{@code EXTRACT} —— 先让 LLM 抽取事实条目再入库，质量高，每轮多一次 LLM 调用。</li>
 * </ul>
 *
 * <p><b>失败策略</b>：embedding / DB 异常一律只告警并降级为空，<b>绝不阻断对话</b>。
 *
 * @author SAI
 */
@Slf4j
public class MysqlVectorLongTermMemory {

    /** 框架注入记忆消息时使用的 name，用于过滤避免把召回的记忆又存回去 */
    private static final String LTM_MSG_NAME = "long_term_memory";

    /**
     * 召回结果的包装模板（与官方 {@code LongTermMemoryTools.wrap} 完全一致）。
     * 官方该类自 2.0.0 起已 {@code @Deprecated(forRemoval=true)}，故在此内联等价实现，避免依赖即将移除的 API。
     */
    private static final String LTM_WRAP_TEMPLATE =
            "Below is content retrieved from the long-term memory associated with the current user, "
                    + "Please extract useful information from it in the context of the current conversation.\n"
                    + "<long_term_memory>\n%s\n</long_term_memory>";

    /** 入库粒度 */
    public enum RecordMode {
        /** 原始消息直接向量化 */
        RAW,
        /** 先用 LLM 抽取事实条目 */
        EXTRACT
    }

    private static final String EXTRACT_PROMPT = """
            你是记忆抽取助手。请从下面的对话中提取值得长期记住的事实。

            要求：
            1. 每条一行，简洁明确，不超过 100 字
            2. 只保留用户偏好、个人信息、重要约定、长期有效的结论
            3. 不要输出编号、不要解释、不要寒暄
            4. 没有任何值得记住的内容时，只输出一行：NO_REPLY
            """;

    /**
     * 语义精简相关的提示词与调用逻辑已统一收敛到 {@link SemanticRefineHandler}，
     * 本类不再内联 REFINE_SYSTEM / REFINE_USER / SAFE_EMBED_CHARS，避免与知识库写入等其它调用方重复维护。
     */

    private final AgentMemoryService memoryService;
    private final EmbeddingClient embeddingClient;
    private final Model extractModel;

    private final String defaultUserId;
    private final String agentName;
    private final RecordMode recordMode;

    private final int topK;
    private final double threshold;
    private final int maxInjectChars;

    private final int tailScan;
    private final int minChars;
    private final int maxPerCall;
    private final int maxQueryChars;
    private final double dedupThreshold;

    // ===== 超长内容「语义精简」后再向量化（逻辑收敛到 SemanticRefineHandler）=====
    /**
     * 语义精简 Handler：原文超过阈值时先由模型压缩为精炼摘要再向量化，
     * 避免长文本直接喂给 bge-m3 触发 Ollama 上下文超限（HTTP 500），并比「机械截断头部」保留更完整语义。
     * 配置（是否启用 / 专用精简模型 / 阈值 / 目标长度）统一在 {@code AgentAIConfig} 装配 Handler 时给定。
     */
    private final SemanticRefineHandler refineHandler;

    public MysqlVectorLongTermMemory(AgentMemoryService memoryService,
                                     EmbeddingClient embeddingClient,
                                     Model extractModel,
                                     String defaultUserId,
                                     String agentName,
                                     RecordMode recordMode,
                                     int topK,
                                     double threshold,
                                     int maxInjectChars,
                                     int tailScan,
                                     int minChars,
                                     int maxPerCall,
                                     int maxQueryChars,
                                     double dedupThreshold,
                                     SemanticRefineHandler refineHandler) {
        this.memoryService = memoryService;
        this.embeddingClient = embeddingClient;
        this.extractModel = extractModel;
        this.defaultUserId = defaultUserId == null ? "" : defaultUserId;
        this.agentName = agentName == null ? "Master" : agentName;
        this.recordMode = recordMode == null ? RecordMode.RAW : recordMode;
        this.topK = topK;
        this.threshold = threshold;
        this.maxInjectChars = maxInjectChars;
        this.tailScan = tailScan;
        this.minChars = minChars;
        this.maxPerCall = maxPerCall;
        this.maxQueryChars = maxQueryChars;
        this.dedupThreshold = dedupThreshold;
        this.refineHandler = refineHandler;
    }

    // ==================== 对外方法（userId 显式传参）====================

    /**
     * 召回：语义检索后返回可直接注入 system prompt 的文本（已包 {@code <long_term_memory>} 块）。
     * 无命中时返回 {@code Mono.empty()}，由调用方决定怎么处理。
     */
    public Mono<String> retrieve(Msg msg, String userId) {
        return doRetrieve(msg, userId == null ? defaultUserId : userId);
    }

    /** 沉淀：把本轮对话中有价值的事实写入向量库（内部扫尾部 + 相似度去重）。 */
    public Mono<Void> record(List<Msg> msgs, String userId, String sessionId) {
        return doRecord(msgs, userId == null ? defaultUserId : userId, sessionId);
    }

    // ==================== 召回 ====================

    private Mono<String> doRetrieve(Msg msg, String userId) {
        String query = extractQuery(msg);
        if (query == null) {
            return Mono.empty();
        }
        if (!embeddingClient.isEnabled()) {
            log.debug("[LTM] embedding 未启用，跳过长期记忆召回");
            return Mono.empty();
        }
        return Mono.fromCallable(() -> embeddingClient.embed(query))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(vec -> memoryService.vectorRecall(userId, vec, topK, threshold))
                .collectList()
                .flatMap(hits -> {
                    if (hits.isEmpty()) {
                        // 空结果：返回 empty，框架 filter 后不会注入任何消息
                        return Mono.<String>empty();
                    }
                    touchAsync(hits);
                    return Mono.just(LTM_WRAP_TEMPLATE.formatted(formatHits(hits)));
                })
                .onErrorResume(e -> {
                    log.warn("[LTM] 长期记忆召回失败（已跳过，不阻断对话）: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** 热度回写：失败可忽略，不参与主链路 */
    private void touchAsync(List<AgentMemoryHit> hits) {
        List<Long> ids = hits.stream()
                .map(AgentMemoryHit::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        memoryService.touchAccess(ids)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.debug("[LTM] 记忆热度回写失败（忽略）: {}", e.getMessage()));
    }

    // ==================== 沉淀 ====================

    private Mono<Void> doRecord(List<Msg> msgs, String userId, String sessionId) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        if (!embeddingClient.isEnabled()) {
            log.debug("[LTM] embedding 未启用，跳过长期记忆沉淀");
            return Mono.empty();
        }
        // 官方传的是全量历史，这里只扫尾部，配合向量去重避免重复入库
        List<Msg> tail = msgs.size() > tailScan
                ? msgs.subList(msgs.size() - tailScan, msgs.size())
                : msgs;

        Mono<List<String>> source = (recordMode == RecordMode.EXTRACT && extractModel != null)
                ? extractFacts(tail)
                : Mono.just(rawCandidates(tail));

        return source
                .flatMapIterable(list -> list)
                .concatMap(text -> saveOne(text, userId, sessionId))
                .then()
                .onErrorResume(e -> {
                    log.warn("[LTM] 长期记忆沉淀失败（已跳过，不阻断对话）: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** RAW 模式：直接从尾部消息中挑候选文本 */
    private List<String> rawCandidates(List<Msg> tail) {
        return tail.stream()
                .filter(this::isWorthy)
                .map(Msg::getTextContent)
                .filter(t -> t != null && t.trim().length() >= minChars)
                .map(String::trim)
                .distinct()
                .limit(maxPerCall)
                .toList();
    }

    /** EXTRACT 模式：先让 LLM 抽取事实条目 */
    private Mono<List<String>> extractFacts(List<Msg> tail) {
        String convo = tail.stream()
                .filter(this::isWorthy)
                .map(m -> m.getRole() + ": " + m.getTextContent())
                .collect(Collectors.joining("\n"));
        if (convo.isBlank()) {
            return Mono.just(List.of());
        }
        List<Msg> prompt = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(EXTRACT_PROMPT).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text(convo).build()).build());
        return extractModel.stream(prompt, null, null)
                .map(ChatResponse::getContent)
                .filter(Objects::nonNull)
                .flatMapIterable(c -> c)
                .ofType(TextBlock.class)
                .map(TextBlock::getText)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .map(this::parseFacts)
                .onErrorResume(e -> {
                    log.warn("[LTM] 事实抽取失败（本轮跳过沉淀）: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private List<String> parseFacts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\\R")) {
            String t = line.trim().replaceFirst("^[-*\\d.\\s]+", "");
            if (t.isBlank() || "NO_REPLY".equalsIgnoreCase(t)) {
                continue;
            }
            if (t.length() >= minChars) {
                out.add(t);
            }
            if (out.size() >= maxPerCall) {
                break;
            }
        }
        return out;
    }

    /** 写入单条：先按相似度去重，再落库 */
    private Mono<Void> saveOne(String text, String userId, String sessionId) {
        // 超长内容先做「语义精简」再向量化（避免直接喂长文本触发 Ollama 上下文超限）；
        // 落库的 content 仍是完整原文，召回/展示不丢内容。
        return refineIfNeeded(text)
                .flatMap(embedText -> Mono.fromCallable(() -> embeddingClient.embed(embedText))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(vec -> memoryService.vectorRecall(userId, vec, 1, dedupThreshold)
                                .collectList()
                                .flatMap(dups -> {
                                    if (!dups.isEmpty()) {
                                        // 已存在高度相似的记忆 → 视为重复，跳过
                                        log.debug("[LTM] 命中重复记忆，跳过: {}", text);
                                        return Mono.<Void>empty();
                                    }
                                    AgentMemoryEntity e = new AgentMemoryEntity();
                                    e.setUserId(userId);
                                    e.setAgentName(agentName);
                                    e.setSessionId(sessionId);
                                    e.setMemoryType("FACT");
                                    e.setContent(text);
                                    e.setSource(recordMode == RecordMode.EXTRACT ? "EXTRACT" : "RAW");
                                    e.setImportance(3);
                                    e.setEmbedding(vec);
                                    e.setAccessCount(0);
                                    return memoryService.saveMemory(e).then();
                                })))
                .onErrorResume(e -> {
                    log.warn("[LTM] 写入单条记忆失败（忽略）: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 超长内容语义精简：委托 {@link SemanticRefineHandler} 完成。
     * Handler 内部按配置阈值决定是否调用模型、并对失败做「回退原文 + 应急硬截断」兜底，绝不阻断沉淀主链路。
     * 落库的 content 仍是完整原文（在 {@link #saveOne} 中写入），召回/展示不丢内容。
     */
    private Mono<String> refineIfNeeded(String text) {
        return refineHandler.refine(text);
    }

    // ==================== 辅助 ====================

    private String extractQuery(Msg msg) {
        if (msg == null) {
            return null;
        }
        String t = msg.getTextContent();
        if (t == null || t.isBlank()) {
            return null;
        }
        t = t.trim();
        return clip(t, maxQueryChars);
    }

    /** 截断到最大长度（<=0 表示不限制） */
    private static String clip(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /**
     * 是否值得入库：仅 USER / ASSISTANT 的消息。
     * 显式排除框架注入的 {@code long_term_memory} 消息，否则会把召回的记忆反复写回，造成记忆放大。
     */
    private boolean isWorthy(Msg m) {
        if (m == null) {
            return false;
        }
        if (LTM_MSG_NAME.equals(m.getName())) {
            return false;
        }
        MsgRole role = m.getRole();
        return role == MsgRole.USER || role == MsgRole.ASSISTANT;
    }

    /** 拼装注入文本，受字符预算约束 */
    private String formatHits(List<AgentMemoryHit> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (AgentMemoryHit h : hits) {
            if (h.getContent() == null || h.getContent().isBlank()) {
                continue;
            }
            String type = h.getMemoryType() == null ? "FACT" : h.getMemoryType();
            String line = i + ". (" + type + ") " + h.getContent().trim();
            if (sb.length() + line.length() > maxInjectChars) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            i++;
        }
        return sb.toString();
    }
}
