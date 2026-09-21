package xsl.sai.agent.handler;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语义提炼 Handler —— 将「长文本压缩为语义完整摘要」的模型调用抽成可复用组件。
 *
 * <p>原实现散落在 {@code MysqlVectorLongTermMemory#refineIfNeeded}（长期记忆沉淀时对超长内容做精简再向量化），
 * 现统一收敛到本 Handler，供<b>长期记忆</b>与<b>知识库写入</b>等多处复用，避免同一套提示词与调用逻辑多处分发。
 *
 * <p><b>能力</b>：用 LLM 把原文提炼为一段保留全部关键实体（人物/时间/地点/结论/数字/专有名词）的精简语义，
 * 用于后续向量化检索；以及从文本中<b>提炼关键词</b>（{@link #extractKeywords}），返回字符串集合，
 * 用于增强向量化文本的关键词命中。两者提炼失败 / 未启用 / 模型为 null 时均安全回退（语义回退原文、关键词回退空集合），
 * 绝不阻断主链路。
 *
 * <p><b>与直接使用 Embedding 的区别</b>：bge-m3 上下文仅 8192 token，超长原文直接喂会触发 Ollama 上下文超限（HTTP 500）；
 * 先提炼再向量化，既能规避超限，又比机械截断头部保留更完整的语义，提升召回质量。
 *
 * <p><b>线程模型</b>：返回 {@link Mono<String>}，底层走 AgentScope {@link Model#stream} 响应式流，不阻塞主线程。
 *
 * @author SAI
 */
@Slf4j
public class SemanticRefineHandler {

    /** 语义精简：系统提示词（%d 由 maxChars 填充） */
    private static final String REFINE_SYSTEM = """
            你是内容精简助手。请将用户提供的长文本压缩为一段语义完整的精简摘要，用于后续向量化检索。
            要求：
            1. 保留全部关键事实、人物、时间、地点、结论、数字与专有名词，丢弃寒暄、重复与冗长铺陈；
            2. 用简洁的第三人称陈述，不要加"摘要："等前缀，不要解释你的操作；
            3. 输出长度控制在 %d 个汉字/字符以内，宁缺毋滥，绝不遗漏关键实体；
            4. 若原文本身就是短文本，直接原样返回即可。
            """;

    /** 语义精简：用户提示词（%1$d=目标长度，%2$s=原文） */
    private static final String REFINE_USER = """
            请对下面的长文本做语义精简，精简后控制在 %1$d 个字符以内（只输出精简结果，不要任何解释）：

            --- 原文 ---
            %2$s
            --- 原文结束 ---
            """;

    /** 关键词提炼：系统提示词 */
    /**
     * 关键词提炼：系统提示词。
     *
     * <p><b>为什么强调「短、少、零解释」</b>：提炼模型是<b>推理型</b>模型，思考（reasoning）与正文
     * 共享 {@code maxTokens} 配额。任务描述越开放、要求的词越长越多，模型思考越久，配额被思考吃光后
     * 正文输出为 0（表现为关键词提炼「静默失败」、整段原文被当作兜底关键词入库）。
     * 因此这里把任务收敛到最简：只挑几个极短的词，不做任何解释与推理。
     *
     * <p><b>为什么不再只挑「人物/地点/机构」等实体</b>：知识库内容多为项目介绍、技术架构、操作说明、
     * 业务概念等，检索者更可能用「主题 / 概念 / 技术栈 / 功能模块 / 产品名 / 动作 / 场景」去查，
     * 而非实体名。若只抽 NER 实体，会漏掉大量检索入口。故这里要求从多个语义维度覆盖，
     * 且以「用户检索时最可能直接输入的查询词」为挑选标准。
     */
    private static final String KEYWORD_SYSTEM = """
            你是知识库检索关键词提取器。任务：从给定文本中提取一批"用户检索时最可能直接输入的查询词"，用于向量检索召回。

            硬性要求：
            1. 直接给出结果，不要思考过程、不要解释、不要任何前言后语；
            2. 提取 6~10 个关键词，从多个维度覆盖文本：核心主题、关键概念、技术名词/技术栈、功能模块、产品/项目名、方法动作、使用场景、问题或解决方案等，不要只局限于人名、地名、机构名等实体；
            3. 优先使用原文中出现过的词或短语，每个关键词尽量精炼（2~8 个字最佳）；允许个别概括性的复合短语（如"向量检索""响应式架构"），但禁止整句，宁可把一个复合概念拆成多个短词；
            4. 每个关键词必须能独立代表文本的一个检索角度，避免彼此高度重复或语义雷同；
            5. 每个关键词单独占一行，不要编号、不要引号、不要标点、不要任何多余符号；
            6. 原文极短或无明显主题时，不输出任何内容（直接返回空）。
            """;

    /** 关键词提炼：用户提示词（%s=原文） */
    private static final String KEYWORD_USER = """
            请从下面的文本中提取关键词（每个关键词单独一行，不要编号与解释）：

            --- 原文 ---
            %s
            --- 原文结束 ---
            """;

    /** 语义分块：系统提示词（%1$d=目标 token 数，%2$s=块间分隔符） */
    private static final String SEGMENT_SYSTEM = """
            你是一个文档语义分块专家。请将用户提供的长文本切分为若干个「语义完整、自包含」的块（chunk）。
            要求：
            1. 严格按【语义/话题/段落】边界切分，绝不把一个完整句子、一个条款、一个步骤从中间切断；
            2. 每个块尽量围绕一个独立主题，长度适中（目标约 %1$d 个 token，但不必精确，宁可保持语义完整）；
            3. 块与块之间用唯一分隔符 "%2$s" 隔开，每个块内部不得再出现该分隔符；
            4. 保留原文全部内容，不得删改、不得概括、不得添加序号或任何说明文字；
            5. 若原文本身很短（不足一个块），则整体作为唯一一块原样返回。
            """;

    /** 语义分块：用户提示词（%1$s=分隔符，%2$s=原文） */
    private static final String SEGMENT_USER = """
            请按上述规则对下面的文本做语义分块（块间用 %1$s 分隔）：

            --- 原文 ---
            %2$s
            --- 原文结束 ---
            """;

    /**
     * 安全兜底字符数：语义精简本身失败时（模型异常 / 返回空），对原文做硬截断，
     * 确保绝不会把超长原文直接发给 bge-m3（其上下文窗口仅 8192 token）。
     * 这是应急保护，正常路径下精简后的摘要远短于该值。
     */
    private static final int SAFE_EMBED_CHARS = 4000;

    /** 默认切块大小（token 数） */
    private static final int DEFAULT_CHUNK_SIZE = 200;
    /** 切块最小长度（过短不切块，整体作为一块） */
    private static final int MIN_CHUNK_LEN = 8;
    /** 语义分块仅对超过该字符数的长文本启用模型分块，短文本直接走硬切兜底（省 LLM 调用） */
    private static final int SEGMENT_MIN_CHARS = 1000;
    /** 语义分块分隔符：模型在块间输出该唯一标记，解析时据此切分 */
    private static final String CHUNK_DELIMITER = "<<<CHUNK>>>";

    /** 语义提炼模型（AgentScope Model，OpenAI 兼容端点）；为 null 时跳过提炼，直接向量化原文 */
    private final Model refineModel;
    /** 是否启用语义提炼 */
    private final boolean enabled;
    /** 精简后目标长度上限（字符），用于约束模型输出规模 */
    private final int maxChars;
    /** 触发提炼的原文长度阈值（字符）：<= 阈值时跳过 LLM，直接返回原文（含应急截断） */
    private final int threshold;

    public SemanticRefineHandler(Model refineModel, boolean enabled, int maxChars, int threshold) {
        this.refineModel = refineModel;
        this.enabled = enabled;
        this.maxChars = maxChars <= 0 ? 800 : maxChars;
        this.threshold = Math.max(threshold, 0);
    }

    /**
     * 语义提炼（使用构造时配置的长度阈值）。
     * 当原文长度未超过阈值（或禁用 / 无模型）时，直接返回应急截断后的原文，不发 LLM 调用。
     *
     * @param text 待提炼原文
     * @return 提炼后文本（失败回退原文）；不可为 null 语义（空串进、空串出）
     */
    public Mono<String> refine(String text) {
        return refine(text, threshold);
    }

    /**
     * 语义提炼（强制模式，覆盖构造阈值）：无论原文长短都尝试提炼（仍受 enabled / model 约束）。
     * 典型用于知识库写入——每个切块都先提炼为精简语义再向量化，提升检索质量。
     *
     * @param text     待提炼原文
     * @param overrideThreshold 本次调用覆盖的长度阈值（<=0 表示不限制，必定提炼）
     */
    public Mono<String> refine(String text, int overrideThreshold) {
        if (text == null) {
            return Mono.just("");
        }
        String clamped = safeClamp(text);
        if (!enabled || refineModel == null || text.length() <= overrideThreshold) {
            // 短文本 / 未启用 / 未配模型：直接返回（仍过一遍应急硬截断兜底）
            return Mono.just(clamped);
        }
        List<Msg> prompt = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(REFINE_SYSTEM.formatted(maxChars)).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text(REFINE_USER.formatted(maxChars, text)).build()).build());
        return refineModel.stream(prompt, null, null)
                .map(ChatResponse::getContent)
                .filter(Objects::nonNull)
                .flatMapIterable(c -> c)
                .ofType(TextBlock.class)
                .map(TextBlock::getText)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .switchIfEmpty(Mono.defer(() -> Mono.just(clamped)))
                .map(SemanticRefineHandler::safeClamp)
                .onErrorResume(e -> {
                    log.warn("[Refine] 语义提炼失败，退回原文: {}", e.getMessage());
                    return Mono.just(clamped);
                });
    }

    /**
     * 关键词提炼（使用构造时配置的长度阈值）。
     * 当原文长度未超过阈值（或禁用 / 无模型）时，直接返回空集合，不发 LLM 调用。
     *
     * @param text 待提炼原文
     * @return 关键词字符串集合（失败 / 未启用 / 无模型时返回空集合，绝不返回 null）
     */
    public Mono<List<String>> extractKeywords(String text) {
        return extractKeywords(text, threshold);
    }

    /**
     * 关键词提炼（强制模式，覆盖构造阈值）：无论原文长短都尝试提炼（仍受 enabled / model 约束）。
     * 典型用于知识库写入——每个切块都先提炼关键词，与手动关键字合并后增强向量化文本的命中能力。
     *
     * @param text              待提炼原文
     * @param overrideThreshold 本次调用覆盖的长度阈值（<=0 表示不限制，必定提炼）
     * @return 关键词字符串集合（失败回退空集合）
     */
    public Mono<List<String>> extractKeywords(String text, int overrideThreshold) {
        if (text == null || text.isBlank()) {
            return Mono.just(Collections.emptyList());
        }
        if (!enabled || refineModel == null || text.length() <= overrideThreshold) {
            // 短文本 / 未启用 / 未配模型：关键词无额外价值，返回空集合
            return Mono.just(Collections.emptyList());
        }
        List<Msg> prompt = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(KEYWORD_SYSTEM).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text(KEYWORD_USER.formatted(text)).build()).build());
        return refineModel.stream(prompt, null, null)
                .map(ChatResponse::getContent)
                .filter(Objects::nonNull)
                .flatMapIterable(c -> c)
                .ofType(TextBlock.class)
                .map(TextBlock::getText)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(SemanticRefineHandler::parseKeywordLines)
                .filter(list -> !list.isEmpty())
                .switchIfEmpty(Mono.defer(() -> Mono.just(Collections.emptyList())))
                .onErrorResume(e -> {
                    log.warn("[Refine] 关键词提炼失败，返回空集合: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /** 把模型返回的多行文本解析为关键词集合：按行拆分、去前缀（"- "/"* "/"数字."）、去重、去空 */
    private static List<String> parseKeywordLines(String raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String k = line.trim();
            // 去掉行首可能的列表前缀："- "、"* "、"• "、"1. "、"2、"
            k = k.replaceFirst("^([-*•]|\\d+[.、)])\\s+", "").trim();
            if (k.isEmpty() || seen.contains(k)) {
                continue;
            }
            seen.add(k);
            out.add(k);
        }
        return out;
    }

    /**
     * 语义分块：用模型把长文本按「语义/话题/段落」边界切成多个自包含的块，替代纯 token 硬切。
     * 模型不可用 / 未启用 / 文本较短（<= {@link #SEGMENT_MIN_CHARS}）时，回落到 {@link #fallbackSplit}
     * （TokenTextSplitter + 字符均分），保证任何情况下都有切块、且不浪费 LLM 调用。
     *
     * <p>与 {@link #refine}、{@link #extractKeywords} 的关系：本方法只负责「切分粒度」，
     * 切出的每个块后续仍会经 {@code refine} 提炼语义、{@code extractKeywords} 提炼关键词后再向量化，
     * 即「模型分块 → 模型提炼 → 模型关键词 → 向量化」的语义增强链路。
     *
     * @param text      待分块原文
     * @param chunkSize 目标块大小（token 数），作为模型分块粒度提示；<=0 用默认值
     * @return 切块集合（顺序即文档顺序；失败回退硬切，绝不返回 null）
     */
    public Mono<List<String>> segment(String text, int chunkSize) {
        if (text == null || text.isBlank()) {
            return Mono.just(Collections.emptyList());
        }
        final int cs = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        if (!enabled || refineModel == null || text.length() <= SEGMENT_MIN_CHARS) {
            // 短文本 / 未启用 / 未配模型：直接走硬切兜底（短文本通常整体成一块）
            return Mono.just(fallbackSplit(text, cs));
        }
        List<Msg> prompt = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder()
                                .text(SEGMENT_SYSTEM.formatted(cs, CHUNK_DELIMITER)).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text(SEGMENT_USER.formatted(CHUNK_DELIMITER, text)).build()).build());
        return refineModel.stream(prompt, null, null)
                .map(ChatResponse::getContent)
                .filter(Objects::nonNull)
                .flatMapIterable(c -> c)
                .ofType(TextBlock.class)
                .map(TextBlock::getText)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .map(raw -> parseSegmentBlocks(raw, cs))
                .filter(list -> !list.isEmpty())
                .switchIfEmpty(Mono.defer(() -> Mono.just(fallbackSplit(text, cs))))
                .onErrorResume(e -> {
                    log.warn("[Refine] 语义分块失败，回退硬切: {}", e.getMessage());
                    return Mono.just(fallbackSplit(text, cs));
                });
    }

    /** 把模型返回的文本按唯一分隔符切成块：去空、对超长单块再硬切兜底，杜绝超长喂给后续 refine/embed */
    private List<String> parseSegmentBlocks(String raw, int chunkSize) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(Pattern.quote(CHUNK_DELIMITER))) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        List<String> result = new ArrayList<>();
        for (String block : out) {
            if (block.length() <= SAFE_EMBED_CHARS) {
                result.add(block);
            } else {
                result.addAll(fallbackSplit(block, chunkSize));
            }
        }
        return result;
    }

    /**
     * 切块兜底：优先 Spring AI {@link TokenTextSplitter}（token 边界），失败时按字符长度均分。
     * 模型分块不可用 / 文本较短时复用本方法，确保切块链路不中断。
     */
    private List<String> fallbackSplit(String content, int chunkSize) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }
        try {
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(chunkSize)
                    .withMinChunkSizeChars(MIN_CHUNK_LEN)
                    .withKeepSeparator(true)
                    .withMaxNumChunks(10000)
                    .build();
            List<Document> docs = splitter.split(List.of(new Document(content)));
            if (docs != null && !docs.isEmpty()) {
                return docs.stream().map(Document::getText)
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("[Refine] TokenTextSplitter 分块失败，回退字符均分: {}", e.getMessage());
        }
        // token ~ 字符粗略换算（中文 1 char≈1 token，英文 ~4 char≈1 token），保守按 1.5*chunkSize 字符
        int step = Math.max(MIN_CHUNK_LEN, (int) (chunkSize * 1.5));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < content.length(); i += step) {
            String s = content.substring(i, Math.min(content.length(), i + step)).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * 合并关键词：将「手动关键字（可能为逗号/空格分隔的多个）」与「模型提炼的关键词集合」去重合并，
     * 拼成向量化前缀（词间空格分隔、末尾换行；都为空则返回空串）。
     * 供知识库写入时把用户手填关键字与模型自动提炼的关键词一起注入向量化文本。
     */
    public static String joinKeywords(String manualKeyword, List<String> extracted) {
        Set<String> set = new LinkedHashSet<>();
        if (manualKeyword != null && !manualKeyword.trim().isEmpty()) {
            for (String k : manualKeyword.split("[,，\\s]+")) {
                String t = k.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        if (extracted != null) {
            for (String k : extracted) {
                String t = k.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        if (set.isEmpty()) {
            return "";
        }
        return String.join(" ", set) + "\n";
    }

    /** 应急硬截断：仅在语义提炼失败且原文超长时拦截，杜绝 Ollama 上下文超限 500。public 供知识库写入兜底（关键词为空时用截断正文作为单个关键词）复用。 */
    public static String safeClamp(String s) {
        if (s == null || SAFE_EMBED_CHARS <= 0 || s.length() <= SAFE_EMBED_CHARS) {
            return s;
        }
        return s.substring(0, SAFE_EMBED_CHARS);
    }
}
