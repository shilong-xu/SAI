# SAI（云枢）

> **版本**：`1.0`（Maven `com.xsl:SAI`）　｜　**许可证**：[Apache License 2.0](./LICENSE)　｜　部署手册见 [`DEPLOY.md`](./DEPLOY.md)　｜　English: [`README.en.md`](./README.en.md)

基于 **Spring Boot 3 + JDK 21 + AgentScope 2.0（Harness）** 的**响应式** AI Agent 平台，产品名「云枢」。

整体采用 **主控编排（Master Orchestrator）+ 声明式子智能体（Sub-Agent）** 架构：一个主 Agent `Master` 负责意图识别、任务分发与结果汇总；所有专业能力由 19 个**声明式**子智能体承担，新增一个只需往 `subagents/` 放一个 `.md` 文件，**不需要改 Java 代码**。

> 技术栈总览：WebFlux 全响应式（R2DBC / Redis / Netty）、AgentScope 2.0 Harness、Spring AI Alibaba（编排图）、Hutool JWT 鉴权、Redisson 分布式锁、Quartz 动态调度、Thymeleaf + Alpine.js + HTMX 服务端渲染控制台、MySQL 9 + `vector(1024)` 向量列 + Ollama `bge-m3` 本地嵌入。

---

## 目录

1. [能力总览](#1-能力总览)
2. [快速开始](#2-快速开始)
3. [模块结构](#3-模块结构)
4. [架构理念与工作区](#4-架构理念与工作区)
5. [子智能体 / 技能 / 工具](#5-子智能体--技能--工具)
6. [环境要求](#6-环境要求)
7. [配置体系](#7-配置体系)
8. [数据库](#8-数据库)
9. [鉴权机制](#9-鉴权机制)
10. [AI 对话链路](#10-ai-对话链路)
11. [邮件模块](#11-邮件模块)
12. [待办事项](#12-待办事项)
13. [知识库与向量检索](#13-知识库与向量检索)
14. [任务调度模块](#14-任务调度模块)
15. [通知模块](#15-通知模块)
16. [统计仪表盘](#16-统计仪表盘)
17. [接口文档](#17-接口文档)
18. [前端控制台](#18-前端控制台)
19. [构建与部署](#19-构建与部署)
20. [扩展指南](#20-扩展指南)
21. [运维与安全注意事项](#21-运维与安全注意事项)
22. [开源协议与贡献](#22-开源协议与贡献)

---

## 1. 能力总览

| 能力 | 说明 |
|------|------|
| **AI 对话** | SSE 流式输出、可中断、**计划模式**（进入/退出/查看/写计划）、思考链展示、Markdown + 代码高亮 + HTML 预览渲染 |
| **多智能体编排** | 主 Agent + 19 个声明式子智能体；按子 Agent 的 `description` 自动选人派发；支持子 Agent 间接力（需求 → 调研 → 架构 → 编码 → 测试 → 报告） |
| **协作空间** | 首页仪表盘 / 待办事项 / 知识库 / 邮件 / 定时任务 / 调度日志 / Agent 任务，共 7 个管理视图 |
| **邮件** | IMAP 抓取入库 + SMTP 发送；🔴 **发送强制两阶段**（先草稿预览、用户明确确认后才发）；支持 HTML 正文渲染 |
| **邮件→Agent 流水线** | 开关式：每封新邮件可异步交 Agent 判定类型并**自动登记为待办**（无人值守，绝不发信） |
| **待办事项** | 增删改查 + 标题模糊搜索 + 完成状态/类型筛选 + 滑块状态开关 + Markdown 内容 + 点击标题看详情 |
| **知识库** | **关键词向量索引**（bge-m3 1024 维）＋ 页面**双通道搜索**（关键词模糊 / 语义检索） |
| **任务调度** | Quartz 动态增删改 / 启停 / **立即执行**（反射调用任意白名单 Bean 方法）/ 执行日志；分「通用任务」与「Agent 任务」 |
| **Agent 任务** | 定时向 Agent 下发提示词（无人值守模式），执行过程同样落调度日志 |
| **统计仪表盘** | 4 个模块卡片（知识库 / 邮件 / 定时任务 / 待办）+ 近 7 日趋势（Token / 邮件 / 调度 / 待办） |
| **分布式并发保护** | Redisson 同步锁保护邮件抓取，多实例/多入口并发时自动跳过 |
| **鉴权** | Hutool JWT（HS256）+ `@NoAuth` 注解开路的 WebFilter，无 Spring Security 依赖 |

---

## 2. 快速开始

```bash
# 0. 准备外部配置（从模板复制，填自己的密码 / API Key）
cp application.yml.example application.yml

# 1. 编译（必须用 JDK 21；首次联网拉依赖）
export JAVA_HOME=/path/to/jdk-21
mvn compile

# 2. 开发启动（sai-admin 聚合 framework/agent/client/schedule）
mvn -pl sai-admin spring-boot:run

# 3. 浏览器打开控制台
#    http://localhost/   默认账号见 application.yml 的 sai.auth.accounts
```

默认监听 `server.port=80`（可在外层 `application.yml` 覆盖为 `8080` 等）。

**前置依赖**：MySQL（含 `vector` 类型支持）+ Redis + Ollama（`ollama pull bge-m3`）+ 大模型 API Key。
**核心对话**只依赖大模型 API，但协作空间（知识库 / 邮件 / 待办 / 调度 / 仪表盘）需要 MySQL 与 Redis。

---

## 3. 模块结构

父 POM：`groupId=com.xsl`、`artifactId=SAI`、`packaging=pom`，父级 `spring-boot-starter-parent:3.5.14`，JDK 21。

| 模块 | 角色 | 说明 |
|------|------|------|
| `sai-admin` | 启动模块（唯一可执行 jar） | `SpringMain` 启动类；聚合各 profile 配置；`spring-boot-maven-plugin` 打可执行 jar |
| `sai-framework` | 基础设施 | R2DBC / Redis 客户端（含 `RedisClient.syncLock`）、鉴权（JWT + `AccountProvider` + `AuthWebFilter`）、`BaseEntity` / `Result`、`MailClient` / `MailReceiver`、`TodoType` 等枚举、`SpringHolder` |
| `sai-client` | 接口与业务层 | 对外 REST API + 业务 Service：对话、会话、知识库、邮件、待办、调度、通知、统计 |
| `sai-agent` | Agent 核心 | 主 Agent 装配（`AgentAIConfig`）、子 Agent 自动加载（`AgentInit`）、自定义 Tool、关键词提炼（`SemanticRefineHandler`）、过程追踪（`AgentTraceMiddleware`） |
| `sai-page` | 前端控制台 | Thymeleaf 服务端渲染 + Alpine.js 响应式：对话页与协作空间 7 个视图 |
| `sai-schedule` | 任务调度 | Quartz 动态调度引擎：`DynamicScheduleManager` + `ScheduleJob` + `TaskInvoker`（反射调用 + 白名单 + 超时） |

**关键依赖**：

- `sai-framework`：`spring-boot-starter-webflux`、`spring-boot-starter-data-r2dbc`、`org.mariadb:r2dbc-mariadb`、`org.redisson:redisson:4.6.0`、`cn.hutool:hutool-all`、`spring-boot-starter-mail`、`io.netty:netty-resolver-dns-native-macos`（macOS 启动 Netty 所需）
- `sai-agent`：`io.agentscope:agentscope-harness:2.0.0`、`io.agentscope:agentscope-extensions-model-openai:2.0.0`、`com.alibaba.cloud.ai:spring-ai-alibaba-graph-core`、`org.springframework.ai:spring-ai-openai`
- `sai-page`：`spring-boot-starter-thymeleaf`
- `sai-schedule`：`spring-boot-starter-quartz`

> `AgentScope` 的 OpenAI 模型提供商已从 `agentscope-core` 拆分为独立扩展 `agentscope-extensions-model-openai`，必须显式引入。

---

## 4. 架构理念与工作区

整体遵循 AgentScope Harness 官方约定：**「工作区目录 + 声明式 Builder」**，能力尽量用框架内置，仅在必要时手写 Tool。

### 4.1 系统指令分层（不硬编码进 Java）

| 文件 | 定位 | 注入方式 |
|------|------|----------|
| `AGENTS.md` | **主 Agent 行为准则（编排层）** —— 只写四类内容：① 身份与编排原则 ② 全局行为准则 ③ 无人值守通用行为 ④ 输出规范与自身文件工具 | 系统提示词 |
| `SOUL.md` | 人格数据盘（姓名/身份/性格/表达细节），每轮**全文注入**，不被后台自动改写 | `agentScope.soulFile` |
| `subagents/*.md` | 子 Agent 声明（frontmatter + 正文） | 框架自动发现并生成派发工具 |
| `skills/<name>/SKILL.md` | 技能（领域操作手册） | 由子 Agent 在 frontmatter 的 `skills:` 声明 |
| `MEMORY.md` + `memory/` | 长期记忆与日记账 | 框架自动 consolidation |

> 🔴 **分层铁律**：主提示词**不写任何「某领域怎么做」的流程 / 字段 / 判定 / 阈值**，一律下沉到对应子 Agent 或技能。子 Agent 提示词是该领域的**唯一事实来源**；主 Agent 依靠子 Agent 的 `description` 选人派发，所以**改变某个子 Agent 的适用场景必须同步改它的 `description`**。

### 4.2 工作区目录（唯一状态来源）

```
.agentscope/workspace/
├── AGENTS.md                  # 主 Agent 行为准则（编排层）
├── SOUL.md                    # 人格数据盘（每轮全文注入）
├── MEMORY.md                  # 长期记忆（consolidation 自动重写）
├── knowledge/                 # 领域知识 / RAG
├── skills/                    # 技能：skills/<name>/SKILL.md
├── subagents/*.md             # 子 Agent 声明（文件名 = agent_id）
├── memory/YYYY-MM-DD.md       # 记忆日记账
└── <hash>/                    # 会话隔离目录（IsolationScope.SESSION）
```

### 4.3 工作区自动初始化（`AgentInit`）

`xsl.sai.agent.runner.AgentInit` 在构建主 Agent **之前**执行，把 classpath 模板初始化到工作区。必要文件无需手工维护清单 —— `subagents/**`、`skills/**`、`knowledge/**` 三类**自动扫描**，新增/删除下次启动即生效；根级 `AGENTS.md` / `MEMORY.md` / `SOUL.md` 为显式短清单。

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `sai.agent.init.enabled` | `true` | 总开关，`false` 时整体跳过初始化 |
| `sai.agent.init.force` | `false` | `false` 仅**补全**缺失文件（运行期记忆不被覆盖）；`true` **先清空受管目录**（`subagents/`、`skills/`、`knowledge/`）并全量覆盖 |

> ⚠️ **本地开发注意**：项目根的 `application.yml` 未覆盖 `sai.agent.init.*`，因此走 classpath 默认值，若模板内为 `force=true` 则**每次启动都会重置 workspace**。
> ⇒ 改子 Agent / 技能 / 主提示词时，**必须改 `sai-agent/src/main/resources/` 下的模板并 `mvn compile` 同步 `target/classes/`**；只改 `.agentscope/workspace/` 里的副本会在下次启动被覆盖。部署侧一般设 `force=false`，需单独同步模板。

### 4.4 隔离与作用域

`AgentAIConfig` 使用 `IsolationScope.SESSION`，每个 `sessionId`（= `conversationId`）拥有独立隔离目录 `<hash>/`。不同会话之间记忆不继承；`AGENTS.md` / `SOUL.md` / `knowledge/**` 对 Agent 是**只读**的。进化应走**记忆**与**技能**两条官方通道。

---

## 5. 子智能体 / 技能 / 工具

### 5.1 子智能体（19 个，位于 `sai-agent/src/main/resources/subagents/`）

| 分类 | 子智能体 | 职责 |
|------|----------|------|
| 编排/通用 | `Analyst` | 通用分析（拆解问题、比较方案、给结论） |
| 研发 | `EngineeringAnalyst` | 需求澄清 / 调研 / 架构设计 / 优化 |
| 研发 | `CodeWriter` | 编码与代码风格 |
| 研发 | `Tester` | 测试与测试报告 |
| 研发 | `GitAgent` | Git 操作与提交规范 |
| 邮件 | `EmailAgent` | **邮件中枢**：收发、草稿两阶段、邮件流水线入口 |
| 邮件 | `EmailTaskAnalyzer` | 把邮件拆解为可执行任务清单 |
| 待办 | `TodoAgent` | 待办数据的查询 / 保存 / 改状态（唯一碰 `todo_item` 的子 Agent） |
| 知识 | `KnowledgeBaseAgent` | 知识库读写（`save_knowledge` / `query_knowledge`） |
| 数据 | `DatabaseAgent` | SQL 查询与写入 |
| 系统 | `ComputerAgent` | 本机/系统类操作 |
| 网络 | `BrowserAgent` | 网页抓取与搜索 |
| 学习 | `StudyAdvisor` / `PaperAdvisor` | 学习计划答疑 / 论文选题与写作 |
| 生活 | `HealthAdvisor` / `FinanceAdvisor` / `TravelPlanner` / `SocialAdvisor` | 健康 / 理财 / 出行 / 社交 |
| 人格 | `PersonalityAgent` | 性格与表达风格相关 |

> 新增子 Agent **只需在 `subagents/` 放一个 `.md` 文件**（frontmatter 声明 `tools:` 与 `skills:`），无需改代码。

### 5.2 技能（4 个，位于 `sai-agent/src/main/resources/skills/`）

| 技能 | 挂载于 | 内容 |
|------|--------|------|
| `mail` | `EmailAgent` | 邮件解析与**发送两阶段**（§四 草稿块格式：只展示 `收件人 / 主题 / 格式 / 正文`） |
| `mail-todo` | `EmailAgent` | 邮件分类（任务/事项/会议/回信）与**是否需要登记为待办**的判定 |
| `article-writer` | `KnowledgeBaseAgent` | 长文写作与知识沉淀 |
| `summary` | `Analyst` | 摘要提炼 |

> 🔴 `skills:` 是**子 Agent 各自在 frontmatter 声明**的，不是主 Agent 的技能。

### 5.3 内置工具（8 个类 / 18 个工具，注册于 `AgentAIConfig#masterToolkit`）

| 工具类 | 工具名 | 说明 |
|--------|--------|------|
| `KnowledgeBaseTool` | `save_knowledge` / `query_knowledge` | 知识库写入与检索（`query_knowledge` 支持 `top_k` / `threshold`） |
| `MailTool` | `send_mail` / `receive_mail` | 发信（支持 HTML）/ 拉取收件箱 |
| `TodoTool` | `query_todo` / `save_todo` / `update_todo_status` | 待办查询 / 保存 / 改状态（🔴 返回文本**不含主键 ID**） |
| `SqlTool` | `execute_sql` / `vector_search` / `execute_write` | 只读 SQL / 向量检索 / 受控写入 |
| `UtilityTool` | `calculate` / `get_current_time` / `date_diff` / `now_plus_days` / `format_timestamp` | 计算与时间工具 |
| `WebSearchTool` | `web_search` | 联网搜索 |
| `WebFetchTool` | `web_fetch` | 网页正文抓取 |
| `ShellTool` | `execute_shell_command` | 白名单 Shell（`sai.agent.shell.enabled` + `allowed-commands`） |

> 🔴 **新增 Tool 必须到 `AgentAIConfig#masterToolkit` 里 `registerTool`** —— 子 Agent frontmatter 的 `tools:` 是按名从该 toolkit 解析的，不注册子 Agent 就用不了。
> 🔴 **依赖方向是 `client → agent`**，agent 模块拿不到 client 的 Service。Tool 要访问业务表请直连 `R2dbcClient`（同库同表，与页面数据一致）。

---

## 6. 环境要求

| 组件 | 版本 | 必需 | 说明 |
|------|------|------|------|
| JDK | **21**（必须） | 是 | 编译与运行；更高版本可能不兼容 |
| Maven | 3.9+ | 是 | 构建 |
| MySQL | 9.x（需 `vector` 支持） | 协作空间 | R2DBC 驱动 `r2dbc-mariadb`；`knowledge_chunk.embedding_vec` 用原生 `vector(1024)` |
| Redis | 7+ | 协作空间 | Redisson 分布式锁；`fail-fast:false` 时连不上不阻断启动 |
| Ollama | 任意 | 向量检索 | 本地 `bge-m3`：`ollama pull bge-m3`（1024 维） |
| 大模型 API | — | 对话必需 | `agentScope.models` 配 `apiBase` / `apiKey` / `modelName` |
| SMTP + IMAP | — | 邮件功能 | 如 QQ 邮箱（授权码，非登录密码） |

---

## 7. 配置体系

配置按 profile 拆分，`sai-admin/src/main/resources/application.yml` 激活各 profile：

| 配置文件 | 归属 | 主要内容 |
|----------|------|----------|
| `application-core.yml` | 全局 | 通用 Spring 设置 |
| `application-data.yml` | 全局 | 数据源 / Redis |
| `application-api.yml` | 全局 | 接口层通用设置 |
| `application-framework.yml` | `sai-framework` | 邮件收发参数（`spring.mail.receive.*`）、鉴权账号、`Result` 相关 |
| `application-client.yml` | `sai-client` | 业务开关（如邮件流水线 `dispatch-to-agent`） |
| `application-agent.yml` | `sai-agent` | 模型、嵌入、工作区、Shell/MCP/知识库开关、编排参数 |
| `application-page.yml` | `sai-page` | Thymeleaf（`cache: true`）、静态资源映射 |
| `application-schedule.yml` | `sai-schedule` | Quartz 线程池、`allowed-beans` 白名单 |

### 7.1 外部覆盖（部署必读）

项目根的 `application.yml` 是**外部覆盖文件**：Spring Boot 会用它**逐键覆盖** classpath 内的同名配置，jar 内独有的键**仍然生效**（即外层只需写要改的项）。

```yaml
server:
  port: 80
spring:
  r2dbc:
    url: r2dbc:mariadb://127.0.0.1:3306/sai?sslMode=disable&allowPublicKeyRetrieval=true
    username: root
    password: ${SAI_DB_PASSWORD}
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: ${SAI_REDIS_PASSWORD}
agentScope:
  models:
    modelName: deepseek-v4-flash
    apiKey: ${SAI_LLM_API_KEY}
    apiBase: https://api.deepseek.com
    temperature: 0.2
    maxTokens: 4000          # thinking 与正文共享该配额，过小会截断正文
  embedding:
    enabled: true
    base-url: http://127.0.0.1:11434
    api-path: /api/embed
    model: bge-m3
    format: ollama
    vector-dimensions: 1024
sai:
  auth:
    jwt-secret: ${SAI_JWT_SECRET}
    accounts:
      - username: admin
        password: ${SAI_ADMIN_PASSWORD}
        display-name: Admin
```

### 7.2 环境变量

| 变量 | 用途 |
|------|------|
| `SAI_DB_PASSWORD` | 数据库密码 |
| `SAI_REDIS_PASSWORD` | Redis 密码 |
| `SAI_LLM_API_KEY` | 大模型 API Key |
| `SAI_MAIL_PASSWORD` | 邮箱授权码 |
| `SAI_JWT_SECRET` | JWT 签名密钥（长随机串） |
| `SAI_ADMIN_PASSWORD` | 后台管理员密码 |

### 7.3 常用开关

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `spring.mail.receive.dispatch-to-agent` | `false` | 抓取到的邮件是否提交给 Agent 处理（见 §11.3） |
| `spring.mail.receive.agent-content-chars` | `4000` | 提交给 Agent 的正文上限 |
| `spring.mail.receive.agent-timeout-minutes` | `5` | 单封邮件的 Agent 处理超时（仅记日志） |
| `sai.agent.init.enabled` / `force` | `true` / — | 工作区初始化（见 §4.3） |
| `sai.agent.shell.enabled` / `allowed-commands` | `true` / 白名单 | Shell 工具 |
| `sai.agent.mcp.enabled` | `false` | MCP 接入 |
| `sai.agent.knowledge.enabled` | `true` | 知识检索能力 |
| `agentScope.harness.maxIters` | `20` | 主 Agent 单轮最大迭代；过小会截断长任务 |
| `spring.thymeleaf.cache` | `true` | 🔴 页面模板缓存，模板改动**必须重启**才生效 |

---

## 8. 数据库

数据库名 `sai`（MySQL 9.x），共 **10 张表**：

| 表 | 用途 |
|----|------|
| `client_conversation` | 会话（标题、置顶、更新时间） |
| `client_message` | 消息（含 token 统计，供仪表盘聚合） |
| `agent_memory` | Agent 记忆 |
| `knowledge_base` | 知识库正文条目 |
| `knowledge_chunk` | **关键词向量索引**（一个关键词一行 + `embedding_vec vector(1024)`） |
| `received_email` | 收发同表（`direction` 0=接收 / 1=发送，含 `html_content`） |
| `todo_item` | 待办事项（`title` / `type` / `content` / `done`） |
| `schedule_task` | 通用定时任务定义 |
| `schedule_log` | 调度执行日志（通用任务与 Agent 任务共用） |
| `notify_message` | 站内通知 |

### 8.1 建表与初始化

- 全量 DDL 在 [`sql/sai.sql`](./sql/sai.sql)。
- 🔴 `SchemaInitRunner` 只覆盖 **7 张固定表**，并**不**建 `todo_item` 等新增业务表 ⇒ **首部署必须手工导入 `sql/sai.sql`**；新增业务表也要手工 `CREATE TABLE` 并把 DDL 同步写回 `sql/sai.sql`。
- 🔴 **`tinyint(1)` 陷阱**：MySQL 的 `tinyint(1)` 会被 R2DBC 当作 **`Boolean`** 读取。
  - 实体字段是 `Integer` ⇒ DDL 必须写 **`tinyint`（不带长度）**；
  - 实体字段是 `Boolean` ⇒ 才用 `tinyint(1)`。
  - 否则报 `No converter found capable of converting from type [java.lang.Boolean] to type [java.lang.Integer]`，症状是「**写入正常、查询 500**」。（参考 `received_email.direction` 与 `todo_item.done` 的注释）

### 8.2 时间与时区

数据库容器时区为 **UTC**，展示层按本地时间格式化（两者相差 8 小时，排查时间类问题时注意）。

---

## 9. 鉴权机制

- **登录**：`POST /api/auth/login`（`{username, password}`）→ 返回 `{token, username, displayName}`，JWT 由 Hutool `JWTUtil` 以 **HS256** 签名（密钥 `sai.auth.jwt-secret`），默认 24 小时过期。
- **账号来源**：`sai.auth.accounts` 列表（`AccountProvider`），可配多账号，不落库。
- **放行**：`@NoAuth` 注解驱动的 `AuthWebFilter`（`@Order(HIGHEST_PRECEDENCE)`）—— 无路径白名单，只认注解。目前 `login` / `logout` 与页面控制器 `PageController` 免鉴权。
- **前端携带**：统一在 `app.js` 的 `api()` 里加 `Authorization: <token>` 头。
- ⚠️ **无角色模型、无 Token 失效机制**（退出登录仅前端清 token），属于轻量内网部署定位。

---

## 10. AI 对话链路

| 接口 | 说明 |
|------|------|
| `POST /agent/chat` | **SSE 流式**（`text/event-stream`，`multipart/form-data`：`data` = `ChatDTO`，可带 `files`）。服务端逐段推送 `Result` |
| `POST /agent/chat/{conversationId}/stop` | 中断当前生成 |
| `POST /agent/plan/enter` / `exit` / `GET /plan/status` / `POST /plan/write` | 计划模式：进入（只读调研、产出计划）/ 退出 / 查状态 / 落盘计划 |

- 前端对话页**故意不设超时**，长任务可一直流式输出。
- 消息文本、计划、思考过程等均持久化到 `client_message` / `client_conversation`。
- 过程可观测：`AgentTraceMiddleware` 输出四类标签日志；跨回调状态以「`sessionId@Agent名`」为键。
- ⚠️ `agentScope.harness.maxIters` 控制单轮最大迭代次数，配置过小会让长任务**中途截断**（当前 20）。
- ⚠️ **空回复不落库**；排查「丢了回复」可找「两条 user 消息相邻且中间无 assistant」的会话。

---

## 11. 邮件模块

`received_email` 收发**同表**，用 `direction` 区分（`0` 接收 / `1` 发送）。

### 11.1 接收（IMAP）

- 抓取入口：`POST /api/email/fetch`（页面「立即抓取」）与定时任务（默认每 5 分钟，见 §14）。
- 流程：拉取收件箱 → 按 `message_id` 去重（唯一索引 `uk_received_email_msgid`）→ 模型提炼摘要 → 入库（同时保存 HTML 正文到 `html_content`）。
- 🔴 **分布式锁**：`fetchAndStore()` 全程持 Redisson 锁（key `sai:lock:email:fetch`，`tryLock()` 立即尝试 + 看门狗续期）。**多入口/多实例并发时抢不到锁直接返回 0** —— 注意「返回 0」表示**本轮未抓取**，而非收件箱没有新邮件。

### 11.2 发送（SMTP）—— 强制两阶段

🔴 **发邮件必须两阶段**：先出**草稿预览**，用户**明确确认后**才真正发送。

- 草稿**只展示四项**：`收件人 / 主题 / 格式 / 正文`（不加标题行、不加字节数提示、不加确认话术）。
- **不算确认**：用户最初的「帮我发封邮件」；「嗯」「好的」「知道了」等含糊回应。内容改过要重新预览。
- 规则分布在三处，**改一处不生效**：`subagents/EmailAgent.md`（发送铁律）、`skills/mail/SKILL.md` §四（草稿块格式）、`AGENTS.md`（主 Agent 守门）。
- 发送成功后走 **`EmailSentEvent`** 入库留痕；🔴 **绝不发布 `EmailEventBus`**（防「自动回复 → 入库 → 又被当新邮件处理」死循环）。

### 11.3 邮件 → Agent 流水线（无人值守）

开关 `spring.mail.receive.dispatch-to-agent`（默认 `false`）。

开启后，每封**新入库**邮件由 `MailAgentDispatcher` 以 `[邮件流水线-无人值守模式]` 前缀**异步**提交给 Agent（`subscribeOn(boundedElastic).subscribe`，不阻塞抓取接口与定时任务）：

1. `EmailAgent` 按 `mail-todo` 技能判定邮件类型（任务 / 事项 / 会议 / 回信）与是否需要登记为待办；
2. 需要登记时交由 `TodoAgent` 保存（先查重，避免重复登记）；
3. **绝不发送任何邮件**。

- 🔴 每封邮件使用**独立会话** `email-bot-<邮件ID>`：框架对同一会话只保留一条后台主循环，共用会话会互相打断。
- 因为是 fire-and-forget，抓取接口返回「新增 N 封」时，待办可能还没出现（Agent 仍在跑）。**它的可见产出就是待办列表里新增的条目**。

---

## 12. 待办事项

表 `todo_item`，接口前缀 `/api/todo`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `varchar(500)` | 标题 |
| `type` | **`tinyint`** | 类型 code：`1` 任务 / `2` 事项 / `3` 会议 / `4` 回信 |
| `content` | `longtext` | 待办内容（**Markdown**，前端渲染） |
| `done` | **`tinyint`** | `0` 未完成 / `1` 已完成 |

- **类型映射**集中在 `sai-framework` 的 `xsl.sai.framework.enums.TodoType`（client 与 agent 共用同一份），提供 `parse(Object)`（兼容数字与中文标签）/ `normalize()` / `labelOf()`。
- **列表列序**：标题 / 待办内容 / 类型 / 状态 / 更新时间 / 操作。
- **搜索与筛选**：`GET /page` 支持三条件（可空 = 不限）—— `keyword`（**仅标题**模糊）、`done`（`0`/`1`）、`type`；返回 `{list, total（筛选命中数）, active（**全局**未完成数，不受筛选影响）}`。
- **交互**：点击**标题**打开详情弹窗（复用知识库详情结构）；列表内**滑块开关**切换完成状态，直接写库（`POST /{id}/toggle`）。

---

## 13. 知识库与向量检索

`knowledge_base` 存正文条目，`knowledge_chunk` 存**关键词向量索引**（一个关键词一行、单独向量化）。

### 13.1 写入（关键词提炼）

长文本（> 1000 字符）先分块，逐块提炼关键词后合并去重 → 逐词调用 Ollama `bge-m3` 生成 1024 维向量 → 写入 `knowledge_chunk`。
关键词提炼提示词位于 `SemanticRefineHandler.KEYWORD_SYSTEM`，覆盖主题 / 概念 / 技术栈 / 功能模块 / 方法动作 / 使用场景等维度（**不局限于人名地名等实体**）。

### 13.2 页面双通道搜索

| 模式 | 接口 | 说明 |
|------|------|------|
| **搜索**（关键词） | `GET /api/knowledge/page?keyword=` | 关键词模糊搜索：`content LIKE` **或**该条目已提炼的 `keyword LIKE` 命中即算，**支持分页** |
| **检索**（语义） | `GET /api/knowledge/semantic?text=&topK=5&threshold=` | 向量召回，按相似度排序，不分页 |

- 工具栏的 **`.search-mode-switch` 滑块**切换两种模式；检索态下表头「状态」→「相似度」且居中。
- `threshold` 建议 **0.3 ~ 0.5**：填 0.8 以上通常召回不到任何结果。

### 13.3 相关接口

`POST /api/knowledge`（保存/更新）、`POST /api/knowledge/extract-keywords`（只提炼不落库，供弹窗预览）、`GET /api/knowledge/{id}`、`DELETE /api/knowledge/{id}`。

> ⚠️ 已知坑：`knowledge_chunk.keyword` 的排序规则与其他表不同（`utf8mb4_unicode_ci` vs `utf8mb4_0900_ai_ci`）。直接用 SQL 客户端以**用户变量**跨表 `LIKE` 比较会报 `1267 Illegal mix of collations`，但**驱动侧 `?` 参数不会**（属假故障，不要因此改表）。验证此类 SQL 请用 `PREPARE/EXECUTE ... USING`。

---

## 14. 任务调度模块

基于 **Quartz**，支持运行时增删改 / 启停 / **立即执行**，执行结果全部落 `schedule_log`。

### 14.1 调度链路

```
① 启动期  DynamicScheduleManager#afterPropertiesSet
           查 status=1 且未删除的任务 → scheduler.scheduleJob(jobDetail, cronTrigger)
           JobDataMap 携带 taskId / taskName / invokeTarget / groupName / concurrent / timeout

② 到点    ScheduleJob#execute（Quartz Job）
           concurrent=0 → 静态原子锁防重入，占用中则记一条 SKIP 日志
           提交到线程池执行，用 Future.get(timeout) 兜超时

③ 反射    TaskInvoker#invoke("beanName.method()")
           校验 sai.schedule.allowed-beans 白名单
           反射无参调用；对返回的 Mono 做 unwrapReactive()（仅阻塞后台线程）

④ 业务    目标 Service 方法
```

### 14.2 两类任务

| 类型 | 说明 |
|------|------|
| **通用任务** | `invoke_target` 形如 `emailReceiveService.fetchAndStore()`，反射调用任意白名单 Bean 方法 |
| **Agent 任务** | 定时向 Agent 下发提示词（`[定时任务-无人值守模式]` 前缀），自主执行、不提问、产出执行摘要 |

### 14.3 配置与注意

- 🔴 被调度表引用的 Service 实现类**必须显式声明 Bean 名**：`@Service("emailReceiveService")`（首字母小写的接口名），否则反射取不到 Bean。
- 🔴 列表型配置**不能用 `@Value`**（读不到），要用 `@ConfigurationProperties`（如 `allowed-beans` 白名单）。
- 任务**种子数据不在 `sql/sai.sql`**，需按 `DEPLOY.md` 手工 INSERT（如 `email-fetch` / `emailReceiveService.fetchAndStore()` / `0 0/5 * * * ?`），否则启动后没有任何定时任务在跑。

---

## 15. 通知模块

`notify_message` 提供站内通知能力（暂无独立页面，供前端轮询/扩展）：

| 接口 | 说明 |
|------|------|
| `GET /api/notify/list?limit=20` | 通知列表 |
| `GET /api/notify/unread-count` | 未读数 |
| `GET /api/notify/sync?afterId=0` | 增量拉取 |
| `POST /api/notify/read/{id}` / `POST /api/notify/read-all` | 单条 / 全部已读 |
| `POST /api/notify/test?title=&content=` | 生成测试通知 |

---

## 16. 统计仪表盘

`GET /api/stats/dashboard`（`StatsServiceImpl`）返回：

- **4 个模块卡片**：知识库（条目数 + 关键词数）、邮件（收件箱 / 已发送 / 待提炼 / 今日）、定时任务（今日执行 / 累计 / 成功率）、待办（待完成 / 累计 / 今日新增）；
- **近 7 日趋势**：Token 消耗、邮件收发、调度执行、待办新增（4 张小图）；
- 模型名、今日 / 累计 Token。

> 趋势口径：邮件与待办按 `create_time` 聚合（衡量**每日新增量**），调度取 `schedule_log` 执行数。

---

## 17. 接口文档

统一前缀 `/api`（对话除外），返回体统一为 `Result`：

```json
{ "code": 200, "message": "success", "data": { } }
```

> 🔴 **`Result` 恒返 HTTP 200**，错误只体现在 body 的 `code`（`200` = 成功）。前端做写操作（保存/删除）时必须取完整 `Result` 再判 `code`，否则**失败也会提示成功**。

### 17.1 认证 `/api/auth`

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/login` | `@NoAuth` | 登录，返回 JWT |
| POST | `/logout` | `@NoAuth` | 退出（前端清 token） |
| GET | `/me` | 需登录 | 当前用户信息 |

### 17.2 对话 `/agent`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat` | SSE 流式对话（multipart：`data` + `files`） |
| POST | `/chat/{conversationId}/stop` | 中断生成 |
| POST | `/plan/enter` | 进入计划模式 |
| POST | `/plan/exit` | 退出计划模式 |
| GET | `/plan/status` | 计划模式状态 |
| POST | `/plan/write` | 写入计划文件 |

### 17.3 会话 `/api/conversation`

`GET /list`、`POST /`（新建）、`GET /{id}/messages`、`PUT /rename`、`PUT /pin`、`DELETE /{id}`

### 17.4 知识库 `/api/knowledge`

`POST /`（保存/更新）、`POST /extract-keywords`、`GET /page`（`pageNum` / `pageSize` / `keyword`）、`GET /{id}`、`DELETE /{id}`、`GET /semantic`（`text` / `topK` / `threshold`）

### 17.5 邮件 `/api/email`

`GET /list`（`keyword` / `direction` / 分页）、`GET /{id}`、`DELETE /{id}`、`POST /fetch`

### 17.6 待办 `/api/todo`

`POST /`（保存/更新）、`GET /page`（`keyword` / `done` / `type`）、`GET /{id}`、`DELETE /{id}`、`POST /{id}/toggle`

### 17.7 定时任务 `/api/schedule`

`GET /page`、`POST /`、`PUT /`、`POST /{id}/toggle`、`POST /{id}/run`（立即执行）、`DELETE /{id}`、`GET /{id}`、`GET /{id}/logs`、`GET /beans`（可选 Bean 白名单）、`GET /logs`

### 17.8 Agent 任务 `/api/agent-schedule`

`GET /page`、`POST /`、`PUT /`、`POST /{id}/toggle`、`POST /{id}/run`、`DELETE /{id}`、`GET /{id}`、`GET /{id}/logs`、`GET /logs`

### 17.9 通知 `/api/notify`

`GET /list`、`GET /unread-count`、`GET /sync`、`POST /read/{id}`、`POST /read-all`、`POST /test`

### 17.10 统计 `/api/stats`

`GET /dashboard`

### 17.11 页面 `/`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 主控制台（`index.html`） |
| GET | `/view/{name}` | 片段（走 `PageController#FRAGMENTS` 白名单） |

> **分页参数约定**：统一 `pageNum` / `pageSize`（`/api/schedule` 与 `/api/agent-schedule` 部分接口用 `page` / `size`，以各控制器签名为准）。

---

## 18. 前端控制台

技术方案：**Thymeleaf 服务端渲染 + Alpine.js 响应式 + HTMX 片段加载 + marked / DOMPurify / highlight.js**（三方库全部本地化，无外网依赖）。

### 18.1 导航结构

```
AI 对话                          （mode=chat）
协作空间
  🏠 首页仪表盘
  待办
    ✅ 待办事项
  知识库
    📚 知识库
  邮件
    ✉️ 邮件
  自动化
    ⏰ 定时任务
    📜 调度日志
    🤖 Agent 任务
```

### 18.2 关键约定（改前端前必读）

| 约定 | 说明 |
|------|------|
| **新增页面固定四处改动** | ① `templates/fragments/<name>.html` ② `index.html` 加侧栏 `snav-link` + `view-pane` ③ `PageController#FRAGMENTS` 白名单 ④ `app.js` 的 `go()` 分支 + `restoreView()` 视图数组 |
| 🔴 **静态资源版本号** | 改 `app.js` / `app.css` **必须递增 `index.html` 的 `?v=N`**（否则浏览器缓存不刷新）；纯模板改动不用 |
| 🔴 **模板缓存** | `spring.thymeleaf.cache: true` ⇒ 模板改动**必须重启应用**，刷新页面无效 |
| **空数据隐藏表头** | 列表统一 `<table class="tbl" x-show="<数据数组>.length">`；空态 `.kb-empty` 与表格**同级**放在容器内（容器自带边框背景，勿再套一层） |
| **Markdown 渲染** | 在 `load*()` 里**一次性预渲染** `html: this.md(content)`，模板用 `x-html`；**勿在模板里直接调 `md()`**（Alpine 每次重算都会跑一遍） |
| 🔴 **顶层键不可重名** | `app.js` 是单个对象字面量，**后定义的键会静默覆盖先定义的**。曾因筛选字段与方法同名导致接口 400（症状还是间歇的） |
| 🔴 **查询串白名单化** | 前端拼参必须白名单 + `String()` 兜底（如 `done` 只放行 `'0'/'1'`），并在 `catch` 里打出真实 URL |
| **复用既有样式** | 工具栏用 `.kb-toolbar` / `.kb-search-box` / `.kb-select`；详情弹窗用 `.kb-detail` / `.modal-card.wide`；滑块用 `.search-mode-switch` / `.todo-switch` |

> 🛠 仓库自带静态自检脚本 `.workbuddy/scripts/page_static_check.py`（检查顶层键重复 / 模板悬空引用 / 标签闭合），**改完前端先在重启前跑一遍**。

---

## 19. 构建与部署

```bash
# 编译（必须 JDK 21）
export JAVA_HOME=/path/to/jdk-21
mvn clean compile

# 开发启动
mvn -pl sai-admin spring-boot:run

# 打包（可执行 jar）
mvn clean package -DskipTests
java -jar sai-admin/target/sai-admin-*.jar
```

**Docker 部署**（含 MySQL / Redis / 应用）请见 [`DEPLOY.md`](./DEPLOY.md)，Ollama 细节见 [`deploy-ollama-bge-m3.md`](./deploy-ollama-bge-m3.md)。

部署要点速记：

- 外部 `application.yml` 挂载到容器（逐键覆盖 jar 内配置）；
- 首次部署需手工导入 `sql/sai.sql`（`SchemaInitRunner` 只建 7 张固定表）；
- 导入 SQL 必须带 `--default-character-set=utf8mb4`，否则中文二次编码乱码；
- 向量能力依赖支持 `vector` 类型的 MySQL 镜像；
- Ollama 需监听 `0.0.0.0:11434` 才能被容器访问。

---

## 20. 扩展指南

| 目标 | 做法 |
|------|------|
| **新增子智能体** | 往 `sai-agent/src/main/resources/subagents/` 放一个 `.md`（frontmatter：`description` / `tools:` / `skills:` / `steps:`），`AgentInit` 自动扫描。🔴 `description` 决定主 Agent 会不会路由到它 |
| **新增技能** | 往 `sai-agent/src/main/resources/skills/<name>/SKILL.md`，并在使用它的子 Agent frontmatter 的 `skills:` 里声明 |
| **新增 Tool** | 写 `@Component` + `@Tool` / `@ToolParam` 的类，并**注册到 `AgentAIConfig#masterToolkit`**；要访问业务表就直连 `R2dbcClient`（agent 模块拿不到 client 的 Service） |
| **新增页面** | 见 §18.2「新增页面固定四处改动」 |
| **新增业务表** | 手工 `CREATE TABLE` + DDL 写回 `sql/sai.sql` + 实体 `@Table`。🔴 字段是 `Integer` 的 0/1 标志，DDL 写 `tinyint`（不带长度） |
| **给仪表盘加模块** | `DashboardVO` 加字段 → `StatsServiceImpl` 加统计与按日聚合（`Mono.zip` 元组上限 8）→ `home.html` 加卡片与小图 → `app.js` 的 `dash` / `dashCls` / `dashLabel` → `app.css` 加主题色四件套 |
| **新增定时任务** | 在 `schedule_task` 插入一行（`invoke_target` 指向白名单 Bean 方法），或在页面「定时任务」里新建 |

> ⚠️ 改完 `sai-agent/src/main/resources/` 下的模板（子 Agent / 技能 / 提示词）**必须 `mvn compile` 同步 `target/classes/`**，否则运行时读到的仍是旧副本。

---

## 21. 运维与安全注意事项

1. **凭据管理**：数据库 / Redis / 大模型 Key / 邮箱授权码 / 管理员密码一律通过**环境变量**注入（见 §7.2），模板见 `application.yml.example`。
   ⚠️ 项目根 `application.yml` 已写入 `.gitignore`，但**若它曾被提交并被 git 跟踪，`.gitignore` 不会自动取消跟踪** —— 上线前请 `git rm --cached application.yml`，并**轮换已暴露过的密钥**、按需清理历史。
2. **邮件两阶段**：任何自动化链路（定时任务 / 邮件流水线）**只产出草稿、绝不自动发信**。
3. **AI 回复不含内部 ID**：给 Agent 的待办工具返回文本刻意**不含主键 ID**（用户按「标题 + 状态」识别）。
4. **`Result` 恒返 HTTP 200**：前端写操作必须判 `code`，不要只看 HTTP 状态码。
5. **模板缓存**：改 Thymeleaf 模板后必须重启应用。
6. **静态资源版本号**：改 `app.js` / `app.css` 记得递增 `?v=N`。
7. **并发保护**：邮件抓取已用 Redisson 锁；Redis 不可用时锁会**降级为无锁执行**（重复拉取由 `message_id` 唯一索引兜底）。
8. **Shell 工具**：`sai.agent.shell.enabled` 是真实危险能力，生产建议关闭或收窄 `allowed-commands` 白名单。
9. **日志目录**：应用日志在 `log/YYYY_M/NN.txt`（**不是** `logs/`），`【REQUEST】` 行含完整查询参数，排查前端参数问题优先看它。
10. **时区**：数据库容器为 UTC，与本地相差 8 小时。

---

## 22. 开源协议与贡献

本项目采用 [Apache License 2.0](./LICENSE)。

### 首次部署的配置准备

1. 复制 `application.yml.example` 为项目根 `application.yml`；
2. 填写数据库、Redis、大模型 API Key（建议用环境变量）；
3. 导入 `sql/sai.sql`（必须带 `--default-character-set=utf8mb4`）；
4. 按 `DEPLOY.md` 插入默认调度任务；
5. 启动应用，浏览器打开 `http://localhost/` 登录。

### 参与贡献

- 提交前请确保：`mvn clean compile` 通过（JDK 21）；改动前端后跑一次 `.workbuddy/scripts/page_static_check.py`；新增业务表已同步 `sql/sai.sql`。
- 提示词类改动（子 Agent / 技能）请遵循 §4.1 的**分层铁律**：领域细节写进对应子 Agent 或技能，不要堆到主提示词。

### 安全披露

如发现安全问题（尤其是凭据泄露、注入、越权），请勿公开提交 Issue，先通过仓库提供的私有渠道联系维护者。
