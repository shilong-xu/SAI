package xsl.sai.agent.config;

import cn.hutool.core.collection.CollUtil;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import xsl.sai.agent.hook.AgentTraceMiddleware;
import xsl.sai.agent.handler.SemanticRefineHandler;
import xsl.sai.agent.memory.LongTermMemoryMiddleware;
import xsl.sai.agent.memory.MysqlVectorLongTermMemory;
import xsl.sai.agent.runner.AgentInit;
import xsl.sai.agent.service.AgentMemoryService;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import xsl.sai.agent.tool.*;
import xsl.sai.framework.client.EmbeddingClient;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AgentScope 主配置 —— 模型 + MasterAgent（声明式全能力装配）
 *
 * <p>整体架构遵循 AgentScope Harness 官方约定（工作区目录 + 声明式 Builder）：
 * <ul>
 *   <li><b>人格/系统指令</b>：落在工作区 {@code AGENTS.md}，由框架 WorkspaceContextHook 每次推理自动注入，
 *       不在 Java 里硬编码 sysPrompt，以 AGENTS.md 为唯一系统提示词来源</li>
 *   <li><b>文件系统</b>：{@link LocalFilesystemSpec}（ROOTED 模式，限定 workspace 内读写 + 宿主 shell）</li>
 *   <li><b>子智能体</b>：工作区 {@code subagents/*.md}（YAML frontmatter + 正文），由 {@code AgentSpecLoader} 自动发现，
 *       框架据此生成派发工具；子 Agent 工具经 {@code tools:} frontmatter 声明</li>
 *   <li><b>技能</b>：工作区 {@code skills/<name>/SKILL.md}，由 {@code FileSystemSkillRepository} 加载，
 *       框架自动挂 HarnessSkillMiddleware</li>
 *   <li><b>Shell（白名单）</b>：保留手写 注入 toolkit。
 *       说明：框架内置 ShellExecuteTool 为宿主 {@code sh -c} 全开、无白名单，不满足“命令由你指定”的诉求，
 *       故此处用白名单版本，并经 UnixCommandValidator 拦截命令串联防注入</li>
 *   <li><b>MCP / 知识库</b>：当前无外部依赖，默认关闭；MCP 可在 workspace 放 tools 配置即开，详见各 @Value 项</li>
 * </ul>
 *
 * @DATE: 2026/7/14
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class AgentAIConfig {

    @Value("${agentScope.models.apiKey}")
    private String apiKey;
    @Value("${agentScope.models.modelName}")
    private String modelName;
    @Value("${agentScope.models.apiBase}")
    private String apiBase;
    @Value("${agentScope.models.temperature:0.7}")
    private Double temperature;
    @Value("${agentScope.models.maxTokens:2000}")
    private Integer maxTokens;
    @Value("${agentScope.harness.maxIters:3}")
    private Integer maxIters;
    @Value("${agentScope.compaction.triggerMessages:60}")
    private Integer triggerMessages;
    @Value("${agentScope.compaction.triggerTokens:0}")
    private Integer triggerTokens;
    @Value("${agentScope.compaction.reserved:20000}")
    private Integer reserved;
    @Value("${agentScope.compaction.keepMessages:25}")
    private Integer keepMessages;
    @Value("${agentScope.compaction.keepTokens:-1}")
    private Integer keepTokens;
    @Value("${agentScope.compaction.keepTokensMin:2000}")
    private Integer keepTokensMin;
    @Value("${agentScope.compaction.keepTokensMax:8000}")
    private Integer keepTokensMax;
    @Value("${agentScope.compaction.keepTokensRatio:0.25}")
    private Double keepTokensRatio;
    @Value("${agentScope.compaction.flushBeforeCompact:true}")
    private Boolean flushBeforeCompact;
    @Value("${agentScope.compaction.offloadBeforeCompact:true}")
    private Boolean offloadBeforeCompact;
    @Value("${agentScope.compaction.truncateArgs.maxArgLength:200}")
    private Integer truncateArgsMaxLen;
    @Value("${agentScope.compaction.truncateArgs.triggerMessages:3}")
    private Integer truncateArgsTriggerMsgs;
    @Value("${agentScope.compaction.truncateArgs.triggerTokens:0}")
    private Integer truncateArgsTriggerTokens;
    @Value("${agentScope.compaction.truncateArgs.keepMessages:2}")
    private Integer truncateArgsKeepMsgs;
    @Value("${agentScope.compaction.truncateArgs.keepTokens:0}")
    private Integer truncateArgsKeepTokens;
    @Value("${agentScope.compaction.truncateArgs.truncationText:…}")
    private String truncateArgsText;
    @Value("${agentScope.workspace:.agentscope/workspace}")
    private String workspace;
    // ===== 文件沙箱（LocalFilesystemSpec）=====
    @Value("${agentScope.filesystem.additionalRoots:}")
    private List<String> fsAdditionalRoots;   // 额外可访问根目录（逗号分隔，空=仅 workspace）
    @Value("${agentScope.filesystem.isolationScope:GLOBAL}")
    private String fsIsolationScope;          // 隔离级别：GLOBAL | USER | SESSION | AGENT

    // ===== Shell 执行开关（白名单命令与执行逻辑由 ShellTool Bean 承载）=====
    // 是否启用 Shell 工具由 ShellTool 上的 @ConditionalOnProperty 控制（sai.agent.shell.enabled）

    // ===== MCP（当前无 server 地址，默认关闭；在 workspace 放 tools 配置即可启用）=====
    @Value("${sai.agent.mcp.enabled:false}")
    private boolean mcpEnabled;

    // ===== 知识库检索（当前无 embedding 端点，默认关闭）=====
    @Value("${sai.agent.knowledge.enabled:false}")
    private boolean knowledgeEnabled;

    // ===== 长期记忆配置 =====
    @Value("${agentScope.memory.flushTrigger:throttled}")
    private String memoryFlushTrigger;
    @Value("${agentScope.memory.flushThrottleMinutes:10}")
    private Integer memoryFlushThrottleMinutes;
    @Value("${agentScope.memory.consolidationMinGapMinutes:30}")
    private Integer memoryConsolidationMinGapMinutes;
    @Value("${agentScope.memory.consolidationMaxTokens:4000}")
    private Integer memoryConsolidationMaxTokens;
    @Value("${agentScope.memory.dailyFileRetentionDays:30}")
    private Integer memoryDailyFileRetentionDays;
    @Value("${agentScope.memory.sessionRetentionDays:30}")
    private Integer memorySessionRetentionDays;

    // ===== 智能体进化通道开关（默认全部开启，见 application-agent.yml 的 agentScope.evolution）=====
    @Value("${agentScope.evolution.memory:true}")
    private boolean evolutionMemory;
    @Value("${agentScope.evolution.skill.enabled:true}")
    private boolean evolutionSkillEnabled;
    @Value("${agentScope.evolution.skill.autoPromote:true}")
    private boolean evolutionSkillAutoPromote;
    @Value("${agentScope.evolution.skill.securityScan:true}")
    private boolean evolutionSkillSecurityScan;
    @Value("${agentScope.evolution.skill.draftsDir:skills/_drafts}")
    private String evolutionSkillDraftsDir;
    @Value("${agentScope.evolution.skill.mainDir:skills}")
    private String evolutionSkillMainDir;
    @Value("${agentScope.evolution.plan.enabled:true}")
    private boolean evolutionPlanEnabled;
    @Value("${agentScope.evolution.plan.planFileDirectory:plans}")
    private String evolutionPlanDir;
    @Value("${agentScope.evolution.toolResultEviction.enabled:true}")
    private boolean evolutionEvictionEnabled;
    @Value("${agentScope.evolution.toolResultEviction.maxResultChars:80000}")
    private Integer evolutionEvictionMaxChars;
    @Value("${agentScope.evolution.toolResultEviction.previewChars:4000}")
    private Integer evolutionEvictionPreviewChars;
    @Value("${agentScope.evolution.toolResultEviction.evictionPath:_eviction}")
    private String evolutionEvictionPath;

    // ===== 人格数据盘（SOUL.md）：人工维护、框架每轮全文注入，不参与后台自动改写 =====
    // 换一种助手性格/风格只需替换该文件，无需改动 AGENTS.md 系统提示词。
    @Value("${agentScope.soulFile:SOUL.md}")
    private String soulFile;

    // ===== 短期记忆存储（Redis）=====
    // 官方默认实现是 JsonFileAgentStateStore（单机文件、不能跨副本、容器重建即丢）。
    // 改用官方 io.agentscope.extensions.redis.state.RedisAgentStateStore：复用现有 RedissonClient，
    // 通过 .redissonClient(...) 装配；该实现已支持版本化乐观锁（saveIfVersion / getVersioned）。
    // 注：官方实现不设 TTL、也无「静默会话清理」能力——本项目按决策「纯官方，放弃自研 TTL/清理」，
    // 冷数据回收完全交给 Redis 服务端的 maxmemory + allkeys-lru 淘汰策略兜底（可能不可控淘汰）。
    @Value("${agentScope.state.redis.enabled:true}")
    private boolean redisStateEnabled;
    @Value("${agentScope.state.redis.keyPrefix:sai:agent:state}")
    private String redisStateKeyPrefix;

    // ===== 长期记忆（MySQL + 向量检索）=====
    // 取代原本的 workspace 文件记忆（MEMORY.md + memory/*.md）：那套只有关键词匹配、无语义检索。
    @Value("${agentScope.longTermMemory.enabled:true}")
    private boolean longTermMemoryEnabled;
    // 入库粒度：RAW=原始消息直接向量化（零 LLM 成本，有噪音）；EXTRACT=先用 LLM 抽取事实条目
    @Value("${agentScope.longTermMemory.recordMode:RAW}")
    private String longTermMemoryRecordMode;
    // 单用户场景下的兜底用户标识（多用户时由 RuntimeContext 的 userId 覆盖）
    @Value("${agentScope.longTermMemory.defaultUserId:}")
    private String longTermMemoryDefaultUserId;
    @Value("${agentScope.longTermMemory.agentName:Master}")
    private String longTermMemoryAgentName;
    @Value("${agentScope.longTermMemory.topK:5}")
    private Integer longTermMemoryTopK;
    @Value("${agentScope.longTermMemory.threshold:0.65}")
    private Double longTermMemoryThreshold;
    @Value("${agentScope.longTermMemory.maxInjectChars:1500}")
    private Integer longTermMemoryMaxInjectChars;
    @Value("${agentScope.longTermMemory.tailScan:6}")
    private Integer longTermMemoryTailScan;
    @Value("${agentScope.longTermMemory.minChars:8}")
    private Integer longTermMemoryMinChars;
    @Value("${agentScope.longTermMemory.maxPerCall:3}")
    private Integer longTermMemoryMaxPerCall;
    @Value("${agentScope.longTermMemory.maxQueryChars:1000}")
    private Integer longTermMemoryMaxQueryChars;
    @Value("${agentScope.longTermMemory.dedupThreshold:0.95}")
    private Double longTermMemoryDedupThreshold;
    // ===== 长期记忆「语义精简」：超长内容用模型压缩后再向量化（仅长期记忆沉淀时使用）=====
    // 专用精简模型名（longTermMemory.refineModel）留空则复用主模型；详见 mysqlVectorLongTermMemory 的构建逻辑。
    /** 是否启用：超长内容先做语义精简再向量化（取代机械截断，保留更完整语义） */
    @Value("${agentScope.longTermMemory.refineEnabled:true}")
    private boolean longTermMemoryRefineEnabled;
    /** 专用精简小模型名（OpenAI 兼容）；留空则复用主模型（agentScope.models.modelName） */
    @Value("${agentScope.longTermMemory.refineModel:}")
    private String longTermMemoryRefineModel;
    /** 触发精简的原文长度阈值（字符），短于此值直接向量化、不精简 */
    @Value("${agentScope.longTermMemory.refineThreshold:1500}")
    private Integer longTermMemoryRefineThreshold;
    /** 精简后目标长度上限（字符） */
    @Value("${agentScope.longTermMemory.refineMaxChars:800}")
    private Integer longTermMemoryRefineMaxChars;
    // 是否保留 workspace 文件记忆（MEMORY.md / memory/*.md）。
    // 默认 false：与 MySQL 向量长期记忆二选一，避免两套记忆并存造成语义割裂。
    @Value("${agentScope.longTermMemory.useFileMemory:false}")
    private boolean useFileMemory;

    /**
     * 模型引入
     */
    @Bean
    public OpenAIChatModel openAIChatModel() {
        log.info("modelName --> {}", modelName);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(apiBase)
                .generateOptions(GenerateOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .cacheControl(true)
                        .build())
                .stream(true)
                .formatter(new OpenAIMultiAgentFormatter())
                .build();
    }

    /**
     * 语义提炼 Handler —— 长期记忆与知识库写入共用的「长文本→精简语义」模型调用组件。
     *
     * <p>原逻辑内联在 {@code MysqlVectorLongTermMemory}，现抽成独立 Handler：
     * 配置了专用精简小模型（{@code longTermMemory.refineModel}）则用专用模型（同一 OpenAI 兼容端点，仅换 modelName），
     * 未配置则复用主模型，避免多一份成本 / 配置；{@code refineEnabled=false} 时传 null（Handler 内部跳过精简）。
     */
    @Bean
    public SemanticRefineHandler semanticRefineHandler(OpenAIChatModel openAIChatModel) {
        Model refineModel = openAIChatModel;
        if (longTermMemoryRefineEnabled
                && longTermMemoryRefineModel != null && !longTermMemoryRefineModel.isBlank()) {
            log.info("[LTM] 长期记忆使用专用精简模型: {}", longTermMemoryRefineModel);
            refineModel = OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(longTermMemoryRefineModel)
                    .baseUrl(apiBase)
                    .generateOptions(GenerateOptions.builder()
                            .temperature(0.2)
                            .maxTokens(1024)
                            .cacheControl(true)
                            .build())
                    .stream(true)
                    .formatter(new OpenAIMultiAgentFormatter())
                    .build();
        }
        Model effectiveRefine = longTermMemoryRefineEnabled ? refineModel : null;
        log.info("[LTM] 语义精简模型: {}（启用={}，阈值={}字符，目标≤{}字符）",
                longTermMemoryRefineEnabled
                        ? (longTermMemoryRefineModel == null || longTermMemoryRefineModel.isBlank()
                            ? "主模型(openAIChatModel)" : longTermMemoryRefineModel)
                        : "未启用",
                longTermMemoryRefineEnabled, longTermMemoryRefineThreshold, longTermMemoryRefineMaxChars);
        return new SemanticRefineHandler(
                effectiveRefine,
                longTermMemoryRefineEnabled,
                longTermMemoryRefineMaxChars,
                longTermMemoryRefineThreshold);
    }

    /**
     * 长期记忆实现 —— MySQL 存储 + 向量语义检索。
     *
     * <p>MySQL 向量长期记忆后端（自研，不实现已弃用的官方 {@code LongTermMemory} 接口），
     * 由 {@link LongTermMemoryMiddleware} 单点驱动：<b>自动召回 + 自动沉淀</b>。
     * 不注册任何记忆工具——维护动作完全不经过模型决策（即官方 STATIC 思路的自定义后端版本）。
     *
     * <p>入库粒度由 {@code agentScope.longTermMemory.recordMode} 切换：
     * RAW（默认，零 LLM 成本）/ EXTRACT（LLM 抽取事实，质量更高，每轮多一次调用）。
     * EXTRACT 复用主模型，生产环境建议改为更轻量的模型以控制成本。
     */
    @Bean
    public MysqlVectorLongTermMemory mysqlVectorLongTermMemory(AgentMemoryService agentMemoryService,
                                                               EmbeddingClient embeddingClient,
                                                               OpenAIChatModel openAIChatModel,
                                                               SemanticRefineHandler semanticRefineHandler) {
        MysqlVectorLongTermMemory.RecordMode mode =
                "EXTRACT".equalsIgnoreCase(longTermMemoryRecordMode)
                        ? MysqlVectorLongTermMemory.RecordMode.EXTRACT
                        : MysqlVectorLongTermMemory.RecordMode.RAW;
        return new MysqlVectorLongTermMemory(
                agentMemoryService,
                embeddingClient,
                openAIChatModel,
                longTermMemoryDefaultUserId,
                longTermMemoryAgentName,
                mode,
                longTermMemoryTopK,
                longTermMemoryThreshold,
                longTermMemoryMaxInjectChars,
                longTermMemoryTailScan,
                longTermMemoryMinChars,
                longTermMemoryMaxPerCall,
                longTermMemoryMaxQueryChars,
                longTermMemoryDedupThreshold,
                semanticRefineHandler);
    }

    /**
     * 长期记忆中间件 —— 自动召回 + 自动沉淀（纯自动，不暴露工具）。
     *
     * <p>官方 {@code io.agentscope.core.hook.Hook} 自 2.0.0 起 {@code @Deprecated(forRemoval=true)}，
     * 2.0 的扩展位是 {@code Middleware}，故这里用 {@link LongTermMemoryMiddleware}；
     * 装配见 {@link #harnessAgent} 的 {@code .middlewares(...)}。
     */
    @Bean
    public LongTermMemoryMiddleware longTermMemoryMiddleware(MysqlVectorLongTermMemory longTermMemory) {
        return new LongTermMemoryMiddleware(longTermMemory, longTermMemoryDefaultUserId);
    }

    /**
     * 主 Agent 工具箱。
     * <p>声明式装配下，文件读写（来自 {@code LocalFilesystemSpec}）、子智能体派发
     * （来自 {@code SubagentDeclaration}）、技能（来自 {@code skillRepositories}）
     * 都由框架自动注入；此处补充数据库查询与受控 Shell 执行能力。
     *
     * <p>所有工具均由 Spring 容器管理（{@code @Component} Bean），此处只做注入 + 注册，
     * 不再手动 {@code new}；{@link ShellTool} 通过 {@code @ConditionalOnProperty} 控制是否启用，
     * 故用 {@link ObjectProvider} 按需获取。
     */
    @Bean
    public Toolkit masterToolkit(SqlTool sqlTool,
                                 WebSearchTool webSearchTool,
                                 WebFetchTool webFetchTool,
                                 UtilityTool utilityTool,
                                 KnowledgeBaseTool knowledgeBaseTool,
                                 MailTool mailTool,
                                 TodoTool todoTool,
                                 EmbeddingClient embeddingClient,
                                 ObjectProvider<ShellTool> shellToolProvider) {
        Toolkit toolkit = new Toolkit();

        // 数据库工具：自定义只读 SQL 查询 + 向量语义检索（基于 R2dbcClient）
        toolkit.registerTool(sqlTool);
//        log.info("已注册数据库工具：execute_sql, vector_search");

        // 知识库工具：save_knowledge（关键词提炼 + 批量向量化写入）；query_knowledge（关键词向量检索 + 回填条目正文）
        toolkit.registerTool(knowledgeBaseTool);
//        log.info("已注册知识库工具：save_knowledge（检索复用 vector_search）");

        // 邮件工具（收发一体，同一 Bean 提供两个工具）：
        //   send_mail    → 基于 MailClient 发送（未配置时返回降级提示）
        //   receive_mail → 基于 MailReceiver 拉取收件箱并返回 JSON（只读，不入库）
        toolkit.registerTool(mailTool);
//        log.info("已注册邮件工具：send_mail, receive_mail");

        // 待办事项工具（与前端「协作空间 → 待办事项」页面同源的表 todo_item）：
        //   query_todo         → 查询待办（关键词 / 完成状态）
        //   save_todo          → 保存待办（同标题幂等去重）
        //   update_todo_status → 按 ID 或标题改完成状态
        toolkit.registerTool(todoTool);
//        log.info("已注册待办工具：query_todo, save_todo, update_todo_status");

        // Shell 执行工具（每次执行前必须用户确认；由 @ConditionalOnProperty 决定是否存在该 Bean）
        ShellTool shellTool = shellToolProvider.getIfAvailable();
        boolean shellEnabled = shellTool != null;
        if (shellEnabled) {
            toolkit.registerTool(shellTool);
//            log.info("Shell 工具已启用（每次执行需用户确认）");
        } else {
            log.info("Shell 工具已关闭（sai.agent.shell.enabled=false）");
        }

        // 框架未内置的「必要」工具：联网检索/抓取、时间、安全计算，统一声明式注册
        toolkit.registerTool(webSearchTool);
        toolkit.registerTool(webFetchTool);
        toolkit.registerTool(utilityTool);
        log.info("已注册内置工具：web_search, web_fetch, get_current_time/date_diff/now_plus_days/"
                + "format_timestamp, calculate, execute_sql, vector_search"
                + (shellEnabled ? ", execute_shell_command" : ""));
        // 知识库检索：检索能力由 SqlTool.vector_search 提供（上方已注册），
        // 其向量化依赖框架 EmbeddingClient（agentScope.embedding.* 配置）。
        // 此处仅做状态核对 + 连通性探针，避免误导性的“缺少端点”提示。
        if (knowledgeEnabled) {
            if (embeddingClient.isEnabled()) {
                String status = embeddingClient.probe();
                if ("ok".equals(status)) {
                    log.info("知识库检索已启用：可通过 vector_search 工具做语义检索"
                            + "（embeddings 端点 {} 探测正常）", embeddingClient.getBaseUrl());
                } else {
                    log.warn("知识库检索已启用，但 embeddings 端点 {} 探测结果={}。"
                                    + "vector_search 在传入 query_text 时可能失败——"
                                    + "请确认该地址运行的是 OpenAI 兼容 embeddings 服务（而非其它进程占用端口）。",
                            embeddingClient.getBaseUrl(), status);
                }
            } else {
                log.warn("知识库检索已启用(knowledge.enabled=true)，但 embedding 客户端未启用"
                        + "(agentScope.embedding.enabled=false)：vector_search 将无法对 query_text 自动向量化，"
                        + "请改用 query_vector_json 或启用本地 embeddings 服务。");
            }
        }
        if (mcpEnabled) {
            log.info("MCP 已启用：框架会自动从 workspace 加载 MCP server 配置（tools 配置）。");
        }

        // 长期记忆不注册任何工具：召回与沉淀全部由 LongTermMemoryMiddleware 自动完成，
        // 不依赖模型主动调用（模型只消费注入到 system prompt 的记忆）。
        if (longTermMemoryEnabled) {
            log.info("长期记忆：纯自动模式（LongTermMemoryMiddleware 负责召回+沉淀），未注册任何记忆工具");
        }
        return toolkit;
    }

    /**
     * 主 Agent —— Master Orchestrator（声明式全能力装配）
     */
    @Bean
    public HarnessAgent harnessAgent(OpenAIChatModel openAIChatModel,
                                     Toolkit masterToolkit,
                                     WebSearchTool webSearchTool,
                                     AgentInit agentInit,
                                     LongTermMemoryMiddleware longTermMemoryMiddleware,
                                     ObjectProvider<RedissonClient> redissonProvider) {
        // 启动优先初始化工作区：在构建主 Agent（会立即读取 workspace 的 AGENTS.md /
        // subagents / skills 等）之前，确保必要文件就位（缺失补全 / force 复位）。
        // init() 幂等，即使被多处触发也仅首次真正生效。
        agentInit.init();

        // 文件沙箱：限定在 workspace 内读写。
        // 隔离级别默认 GLOBAL（空命名空间，直接落工作区根目录），可经 agentScope.filesystem.isolationScope 配置：
        // USER 会把 MEMORY.md 与 memory/YYYY-MM-DD.md 写进 <userId>/ 子目录，导致根目录 MEMORY.md 不会即时更新
        // （需手工镜像或等 consolidation）；GLOBAL 则解析为空路径段，记忆与文件全部落在根目录，
        // 由框架自身的 flush(写 memory/日记) + consolidation(重写根 MEMORY.md) 直接、自动地维护根记忆，
        // 无需任何手写同步。个人助手单用户场景本就该共享同一份全局记忆。
        // SESSION 会按 <sessionId>/ 隔离，跨会话读不到历史记忆；USER 会按 <userId>/ 隔离，根目录不更新。
        // 额外根目录 additionalRoots 允许 agent 访问 workspace 之外的目录（逗号分隔，空=不开放）。
        IsolationScope fsIso = IsolationScope.valueOf(fsIsolationScope.trim().toUpperCase());
        LocalFilesystemSpec fsSpec = new LocalFilesystemSpec()
                .project(Paths.get(workspace))
                .projectWritable(false)
                .additionalRoots(CollUtil.isEmpty(fsAdditionalRoots) ? List.of() : fsAdditionalRoots.stream().map(Paths::get).toList())
                .mode(LocalFsMode.UNRESTRICTED)
                .isolationScope(fsIso);

        // 子智能体由框架 SubagentsHook 在 build() 时自动从 workspace/subagents/*.md 加载，
        // 无需显式 loadSubagents()；框架据此生成 agent_spawn/agent_send 委派工具（支持后台异步并行）。

        // 系统提示词统一以工作区 AGENTS.md 为准（框架 WorkspaceContextHook 在 PreReasoning 自动拼装注入），
        // 不再硬编码兜底 sysPrompt。人格数据盘 SOUL.md 由 additionalContextFile 每轮全文注入。
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("Master")
                .model(openAIChatModel)
                .toolkit(masterToolkit)
                .workspace(Paths.get(workspace))
                .additionalContextFile(soulFile)
                .maxIters(maxIters)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(triggerMessages)
                        .triggerTokens(triggerTokens)
                        .reserved(reserved)
                        .keepMessages(keepMessages)
                        .keepTokens(keepTokens)
                        .keepTokensMin(keepTokensMin)
                        .keepTokensMax(keepTokensMax)
                        .keepTokensRatio(keepTokensRatio)
                        .flushBeforeCompact(flushBeforeCompact)
                        .offloadBeforeCompact(offloadBeforeCompact)
                        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                                .maxArgLength(truncateArgsMaxLen)
                                .triggerMessages(truncateArgsTriggerMsgs)
                                .triggerTokens(truncateArgsTriggerTokens)
                                .keepMessages(truncateArgsKeepMsgs)
                                .keepTokens(truncateArgsKeepTokens)
                                .truncationText(truncateArgsText)
                                .build())
                        .build());

        // ⓪ 短期记忆存储：框架默认是 JsonFileAgentStateStore（单机文件），
        //    不能跨副本共享、容器重建即丢。切到官方 RedisAgentStateStore 后，同一 (userId, sessionId)
        //    在任意副本上都能续上上下文（故障转移 / 滚动发布无感）。
        //    RedissonClient 由 framework 的 RedisConfig 提供；fail-fast=false 时可能为 null，
        //    此时降级回框架默认文件存储，保证启动不被阻塞。
        //    按「纯官方」决策：不在此处做任何 TTL 设置与清理，冷数据回收交给 Redis 服务端 maxmemory+LRU。
        if (redisStateEnabled) {
            RedissonClient redisson = redissonProvider.getIfAvailable();
            if (redisson != null) {
                builder.stateStore(RedisAgentStateStore.builder()
                        .redissonClient(redisson)
                        .keyPrefix(redisStateKeyPrefix)
                        .build());
                log.info("短期记忆存储已切至官方 RedisAgentStateStore：keyPrefix={}"
                        + "（TTL/清理已在决策中放弃，冷数据由 Redis 服务端 maxmemory+LRU 兜底）",
                        redisStateKeyPrefix);
            } else {
                log.warn("agentScope.state.redis.enabled=true，但 RedissonClient 不可用（未配置或 fail-fast=false）"
                        + "——已降级为框架默认文件状态存储（单机，不能跨副本）");
            }
        }

        // ① 长期记忆：两套方案二选一（由 agentScope.longTermMemory.useFileMemory 决定）
        //   - useFileMemory=true ：保留框架内置文件记忆流水线（MEMORY.md + memory/*.md，仅关键词匹配）
        //   - useFileMemory=false（默认，本次改造目标）：关闭文件记忆，改走 MySQL 向量长期记忆
        // useFileMemory 管「用哪套后端」，longTermMemoryEnabled 管「MySQL 向量记忆本身是否启用」。
        if (useFileMemory) {
            // 保留 workspace 文件记忆
            if (evolutionMemory) {
                builder.memory(MemoryConfig.builder()
                        .flushTrigger(buildMemoryFlushTrigger())
                        .consolidationMinGap(Duration.ofMinutes(memoryConsolidationMinGapMinutes))
                        .consolidationMaxTokens(memoryConsolidationMaxTokens)
                        .dailyFileRetentionDays(memoryDailyFileRetentionDays)
                        .sessionRetentionDays(memorySessionRetentionDays)
                        .build());
            }
        } else {
            // 关闭框架自带的文件记忆：disableMemoryHooks() 关 flush/consolidation，
            // disableMemoryTools() 关 memory_search / memory_get / memory_save / session_search。
            // 两者一起用时框架也不再注入 <memory_context>（MEMORY.md）——
            // 长期记忆改由 MySQL 向量库承载，且维护动作全部由 LongTermMemoryMiddleware 自动完成，
            // 不给模型留任何「自己读写记忆」的入口，避免两套记忆并存 + 模型乱写噪音。
            builder.disableMemoryHooks()
                    .disableMemoryTools();
            if (longTermMemoryEnabled) {
                log.info("长期记忆后端已切换为 MySQL 向量存储（纯自动模式）："
                        + "LongTermMemoryMiddleware 负责召回与沉淀，已关闭框架文件记忆(hooks+tools)");
            }
        }

        // ② 自学习技能（skills/）：agent 从成功模式起草新技能，经审批闸门(autoPromote=false)后成为可复用能力，
        //    后台 curator 把长期未用的标记为 stale(30天) 并归档(90天)。由 agentScope.evolution.skill.* 控制。
        if (evolutionSkillEnabled) {
            builder.enableSkillManageTool(SkillManageConfig.builder()
                    .autoPromote(evolutionSkillAutoPromote)
                    .securityScan(evolutionSkillSecurityScan)
                    .draftsDir(evolutionSkillDraftsDir)
                    .mainDir(evolutionSkillMainDir)
                    .build());
        }

        // ③ 计划文件（plans/）：Plan Mode 写下的计划持久化、跨调用保留。由 agentScope.evolution.plan.* 控制。
        if (evolutionPlanEnabled) {
            builder.enablePlanMode();
            if (evolutionPlanDir != null && !evolutionPlanDir.isBlank()) {
                builder.planFileDirectory(evolutionPlanDir);
            }
        }

        // ④ 工具结果落盘：单条工具结果超阈值则整条写盘，上下文只留 head/tail 预览 + read_file 指针。
        //    由 agentScope.evolution.toolResultEviction.* 控制；关闭时显式 disableToolResultEviction()。
        if (evolutionEvictionEnabled) {
            builder.toolResultEviction(ToolResultEvictionConfig.builder()
                    .maxResultChars(evolutionEvictionMaxChars)
                    .previewChars(evolutionEvictionPreviewChars)
                    .evictionPath(evolutionEvictionPath)
                    .build());
        } else {
            builder.disableToolResultEviction();
        }

        builder.enableMetaTool(true)
                .filesystem(fsSpec);
        // 统一可观测性：工具调用 / 意图分析 / 模型调用 / Agent 生命周期日志全部收口到中间件。
        // 长期记忆中间件与它一起注册（官方：自定义 middleware 跑在 Harness 内置之前）。
        // 注意：Hook（io.agentscope.core.hook.Hook）自 2.0.0 起 @Deprecated(forRemoval=true)，
        // 一律走 Middleware，不再用 builder.hooks(...)。
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(new AgentTraceMiddleware());
        if (!useFileMemory && longTermMemoryEnabled) {
            middlewares.add(longTermMemoryMiddleware);
        }
        builder.middlewares(middlewares);
        // 自动恢复挂起(ASKING)的工具调用：个人助手场景无人工确认处理器，
        // 写入类工具默认会被 HITL 暂停并抛 500。开启后框架会自动重跑挂起的调用，
        // 既能解开已持久化为暂停态的旧会话，也能防止任何会话因工具确认卡死。
        builder.enablePendingToolRecovery(true);

        HarnessAgent agent = builder.build();

        // ⑤ 夺回 web_search：HarnessAgent.Builder#build() 会**无条件**把框架自带的
        //    WebTools（Tavily 版 web_search / web_fetch）注册进同一个 Toolkit，
        //    且注册顺序在我们的 masterToolkit 之后 → 同名工具被后者覆盖。
        //    未配置 TAVILY_API_KEY 时，模型调用 web_search 只能拿到
        //    "Error: TAVILY_API_KEY is not set. ..."，对外表现为「搜索引擎接口调不通 / 没配密钥」。
        //    框架没有 disableWebTools 之类的开关（Builder 只有 disableFilesystemTools /
        //    disableShellTool / disableMemoryTools …），因此只能在 build() 之后把本项目实现注册回去。
        //    工具 schema 是每轮推理实时从 Toolkit 读取的（框架支持运行期动态挂载技能工具），故此处生效。
        Toolkit effectiveToolkit = agent.getToolkit();
        effectiveToolkit.removeTool("web_search");
        effectiveToolkit.registerTool(webSearchTool);
        log.info("已用本项目实现覆盖框架内置的 Tavily 版 web_search（零密钥，Bing 结果页解析）");

        return agent;
    }

    /**
     * 根据配置文件中的 flushTrigger 字符串构建对应的 FlushTrigger 实例
     */
    private MemoryConfig.FlushTrigger buildMemoryFlushTrigger() {
        return switch (memoryFlushTrigger.toLowerCase()) {
            case "always" -> MemoryConfig.FlushTrigger.always();
            case "never" -> MemoryConfig.FlushTrigger.never();
            default -> MemoryConfig.FlushTrigger.throttled(
                    Duration.ofMinutes(memoryFlushThrottleMinutes));
        };
    }

}
