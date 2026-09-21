# SAI 项目扫描报告

> 扫描时间：2026-08-18 ｜ 扫描路径：`<PROJECT_ROOT>`
> 对比基线：`SCAN_REPORT.md`（2026-08-17）
> Git HEAD：`4e44c4c 升级文档`（master，与 origin/master 同步）
> **结论：结构健康的响应式 AI Agent 平台；相较 2026-08-17，文档/前端继续完善，但安全态势无任何改善——此前标注「已修复 / 已移除」的 4 处明文凭据（DeepSeek Key、MariaDB 密码、Redis 密码、QQ 邮件授权码）至今仍原样存在于已跟踪源码文件中，且 Git 历史中可追溯到「修复后又被写回」的回归。必须立即轮换并清理。**

---

## 0. 相对上次扫描（2026-08-17）的关键变化

| 变化 | 说明 |
|------|------|
| 新增提交 | 4 个：`a7ce7ea 重构前端` / `4963c6b 重构前端` / `af842d5 更新项目文档` / `4e44c4c 升级文档`（HEAD 从 `124394c` 前移） |
| 代码量 | 132 个 Java 文件 / 12,595 行（与 2026-08-17 的 132 / 12,559 基本一致，仅 +36 行微调） |
| 前端 | `sai-page` 视图片段进一步整合（22 html / 12 js / 2 css） |
| 🔴 **安全** | **无改善**。4 处明文凭据全部仍在 `application*.yml` 源码中（见 §6）。Git 历史显示曾于 `288f1bb`「移除明文凭据」，但随后 `f6a4797 修改编排为 agentLoop` 又把 DB/Redis 密码写回；DeepSeek Key 自 2026-07-22 起从未移除 |
| 局部改善 | `sai-admin/.../application.yml:20` 的 DB **连接 URL** 已改为 `${SAI_DB_URL:...}` 环境变量注入（仅密码仍硬编码）；外部覆盖文件 `sai-config.yml` 已移除，部署参数统一走 `application*.yml` 与 `${SAI_*}` 环境变量 |

---

## 1. 项目概览

| 项 | 内容 |
|----|------|
| 名称 | SAI（云枢，数字分身 AI Agent 平台） |
| 类型 | Maven 多模块 Spring Boot 应用（聚合 POM） |
| 定位 | 基于 **Spring Boot 3 + JDK 21 + AgentScope 2.0 (Harness)** 的响应式 AI Agent 平台，采用「主控编排 Master + 声明式子智能体 Sub-Agent」架构 |
| 技术栈 | WebFlux 全响应式（R2DBC / Redis / Netty）、AgentScope 2.0 + Spring AI Alibaba、Hutool JWT、Thymeleaf + Alpine.js + HTMX 服务端渲染控制台 |
| groupId / version | `com.xsl` / `1.0-SNAPSHOT` |
| Spring Boot | 3.5.14 ｜ JDK 21 |
| 代码规模 | **132 个 Java 文件，约 12,595 行**（不含前端模板/脚本/子智能体定义） |

---

## 2. 模块结构（含当前源码量）

| 模块 | Java 文件 | 行数 | 角色 |
|------|----------:|-----:|------|
| `sai-admin` | 1 | 21 | 启动模块（唯一可执行 jar），`SpringMain` 入口，聚合 profile |
| `sai-framework` | 28 | 3,450 | 基础设施：R2DBC/Redis 客户端、JWT 鉴权、统一 Result、MailClient、EmbeddingClient、SchemaInit |
| `sai-client` | 68 | 4,377 | 接口层：REST Controller / Service / Mapper / DTO / VO |
| `sai-agent` | 25 | 3,617 | Agent 核心：`AgentAI` agentLoop 编排、`AgentAIConfig`、11 个 Tool、链路追踪 |
| `sai-page` | 1 | 50 | 前端控制器（Thymeleaf + Alpine + HTMX），页面配置在 `application-page.yml` |
| `sai-schedule` | 9 | 1,044 | Quartz 动态定时任务引擎 |
| **合计** | **132** | **12,595** | |

> 分层规范（controller→service→impl→mapper→entity→dto/vo），结构健康。

---

## 3. Agent 体系

- **子智能体：18 个**。位于 `.agentscope/workspace/subagents/*.md`，框架 `AgentSpecLoader` 自动发现。
- **技能：3 个** — `article-writer`、`mail`、`summary`。
- **工具（Tool）：8 个** — `KnowledgeBaseTool`、`MailTool`、`ShellTool`（白名单版）、`SqlTool`、`UtilityTool`、`WebFetchTool`、`WebSearchTool`。
- **编排**：`AgentAI` 单循环（agentLoop）+ 后台 loop 解耦（SSE 断连不中断工具执行）。

---

## 4. 数据层

`sql/sai.sql`（MySQL 9.x dump），R2DBC 驱动 `r2dbc-mariadb`。**9 张表**：`agent_memory`、`client_conversation`、`client_message`、`knowledge_base`、`knowledge_chunk`、`notify_message`、`received_email`、`schedule_task`、`schedule_log`。向量列 `vector(1024)` 依赖 MySQL 9.x 原生 Vector（MariaDB 需 myvector 插件）。`sql/sai.sql` 列 `COMMENT` 仍为乱码（dump 编码问题）。

---

## 5. 前端控制台

`sai-page`：22 个 HTML 片段、12 个 JS、2 个 CSS；第三方库本地化（alpine/htmx/marked/highlight/purify），无外网依赖。`PageController` 单文件（50 行），`FRAGMENTS` 白名单防路径探测。

---

## 6. ⚠️ 安全与配置风险（高优先级，本次重点确认）

### 6.1 🔴 明文凭据总表（全部位于已跟踪源码文件，已泄露，必须轮换）

| 严重度 | 文件:行 | 凭据类型 | 处理状态（2026-09-20 开源前清理） |
|--------|---------|----------|----------------------------------|
| **CRITICAL** | `sai-agent/src/main/resources/application-agent.yml:18` | DeepSeek API Key | ✅ 源码已改为 `${SAI_LLM_API_KEY:}`；⚠️ 历史里的明文仍需轮换密钥 / 清理历史 |
| **HIGH** | `sai-admin/src/main/resources/application.yml` | MariaDB 密码 | ✅ 已改为 `${SAI_DB_PASSWORD:}` |
| **HIGH** | `sai-admin/src/main/resources/application.yml` | Redis 密码 | ✅ 已改为 `${SAI_REDIS_PASSWORD:}` |
| **MEDIUM** | `sai-framework/src/main/resources/application-framework.yml` | QQ 邮箱授权码 | ✅ 已改为 `${SAI_MAIL_PASSWORD:}`（不再有明文兜底） |
| MEDIUM | `sai-admin/src/main/resources/application.yml` | 后台管理员密码 | ✅ 默认改为 `admin123`，真实值走 `${SAI_ADMIN_PASSWORD}` |
| LOW | `sai-admin/.../application.yml` | 演示账号 `admin123` / `user123` | 设计内演示账号，生产须改 |
| LOW | `AccountProvider.java` | JWT 兜底密钥 | 未配置时开发兜底（仅本地，有告警） |

> ⚠️ 上表涉及的真实凭据**在 git 历史中仍然存在**（DeepSeek Key 自 2026-07-22 起入库）。
> 开源发布前请务必：**① 轮换所有密钥**；**② 用 `git filter-repo` 清理历史**；**③ force push**。

> 注：`sai-admin/target/classes/application.yml` 与 `sai-agent/target/classes/application-agent.yml`、`sai-framework/target/classes/application-framework.yml` 为编译产物，同样含上述明文（已在 `.gitignore` 的 `target/` 内，**不会进版本库**，但本机磁盘上仍暴露，属低优先）。

### 6.2 外部覆盖文件 sai-config.yml（已移除）

- `sai-config.yml` 已于 2026-08-25 从仓库删除，外部覆盖机制取消；运维参数现统一在 `application*.yml` 与 `${SAI_*}` 环境变量中配置。
- 该文件曾含 DB/Redis 明文凭据（注释态），现已随文件删除一并消除。
- DeepSeek Key 在源码 `application-agent.yml` 中仍以明文形式存在（见 §6.1），须尽快轮换。

### 6.3 Git 历史回归轨迹（凭据「修复」名不副实）

| 提交 | 说明 |
|------|------|
| `288f1bb` | `security: 移除明文凭据，改为环境变量注入（SAI_DB_*/SAI_MAIL_*）` —— **曾一度修复** |
| `f6a4797` | `修改编排为 agentLoop` —— **DB/Redis 密码在此被重新写回 `application.yml`** |
| 此后至今 | 仅文档/前端提交，**4 处凭据从未被再次移除** |

> 即：凭据曾在 `288f1bb` 被移除，又在 `f6a4797` 被写回，且 DeepSeek Key 从未真正脱敏。当前 `application*.yml` 中的明文即为 `f6a4797` 及其后续状态。

### 6.4 建议（按优先级）

1. **立即轮换**以下 4 个凭据（视为已泄露）：DeepSeek Key `sk-ad78c05c…`、MariaDB `***REMOVED***`、Redis `***REMOVED***`、QQ 授权码 `***REMOVED***`。
2. **改为环境变量注入，并去掉兜底明文**：
   - `application-agent.yml:5` → `apiKey: ${SAI_LLM_API_KEY:}`
   - `application.yml:20` → `password: ${SAI_DB_PASSWORD:}`
   - `application.yml:28` → `password: ${SAI_REDIS_PASSWORD:}`
   - `application-framework.yml:19` → `password: ${SAI_MAIL_PASSWORD:}`（**删除 `:***REMOVED***` 兜底**）
3. ~~将 `sai-config.yml` 加入 `.gitignore`~~（该文件已删除，外部覆盖机制取消）。
4. 用 `git filter-repo` / BFG 从 Git 历史清除上述凭据（已进入 `f6a4797`、`288f1bb` 等提交），并轮换。
5. 生产部署统一走 `${SAI_*}` 环境变量注入；设置强 JWT `sai.auth.jwt-secret`。

---

## 7. 文档与实现差异（Doc Drift，持续存在）

- **子智能体数量不一致（已修复）**：README 原称「26 个」，经审计与合并后实际 **18 个**（已合并 StudyAdvisor/EngineeringAnalyst、Tester 吸收 TestReportWriter、CodeWriter 吸收 CodeStyleAnalyst；CookingAdvisor/ShoppingAdvisor 并入 Health/Finance）。
- **编排描述滞后**：README 第 1 节仍描述「Master 编排 + 子 Agent 派发」，未反映 `agentLoop` / `AgentAI` 单循环重构。
- 邮件前缀、外部配置覆盖机制等描述与代码一致。

---

## 8. 代码质量观察

- 分层规范、依赖版本锁定清晰（Spring AI BOM `1.1.2` ↔ SAA `1.1.2.0` ↔ Boot `3.5.x`）。
- 前端静态库本地化，无外网依赖，利于离线部署。
- **单元测试缺失**：`src/test` 下 0 个测试文件；常规打包为 `mvn package -DskipTests`。
- `node_modules/playwright*/LICENSE` 显示为未跟踪（仅前端 UI 测试用途，与 Java 构建无关）。

---

## 9. 其它目录

- `log/2026_8/`：运行日志，被 `.gitignore` 的 `log/**` 忽略，不进版本库。
- `scripts/reembed_words.py`：重算单词 embedding 向量脚本（依赖 embeddings 服务）。
- `deploy-ollama-bge-m3.md`：Ollama + bge-m3 向量化部署指引。
- `README.en.md`：英文版 README，与中文版同步（同样存在 26→28 文档漂移）。

---

## 10. 建议总览（按优先级）

1. **【紧急】轮换 4 处明文凭据**并清理 Git 历史（见 6.4）。
2. **【高】** 将所有密钥统一走 `${SAI_*}` 环境变量，去掉 `application-framework.yml:19` 的兜底明文。
3. **【中】** README 子智能体清单 26→28；补充 agentLoop 编排说明。
4. **【低】** `sql/sai.sql` 用正确编码重新导出，修复 COMMENT 乱码。
5. **【低】** 补单元测试（当前 0 测试）。
6. **【低】** 评估 `.agentscope/workspace/{MEMORY,AGENTS,SOUL}.md` 是否含不应入库的个人数据（当前按设计 tracked）。

---

*本报告由项目扫描生成，覆盖源码结构、Agent 体系、数据层、前端、安全与文档一致性。未执行编译/构建。凭据行已定位到确切文件行号，便于快速处置与轮换。*
