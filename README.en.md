# SAI (云枢 / Yún Shū)

> **Version**: `1.0` (Maven `com.xsl:SAI`)　｜　**License**: [Apache License 2.0](./LICENSE)　｜　Deployment guide: [`DEPLOY.md`](./DEPLOY.md)　｜　中文: [`README.md`](./README.md)

A **reactive** AI Agent platform built on **Spring Boot 3 + JDK 21 + AgentScope 2.0 (Harness)**, product name "云枢" (Yún Shū).

It follows a **Master Orchestrator + declarative Sub-Agent** architecture: one master agent, `Master`, handles intent recognition, task dispatch, and result aggregation; every domain capability is carried by 19 **declarative** sub-agents. Adding a new one is just dropping a `.md` file into `subagents/` — **no Java change required**.

> Stack overview: fully reactive WebFlux (R2DBC / Redis / Netty), AgentScope 2.0 Harness, Spring AI Alibaba (orchestration graph), Hutool JWT auth, Redisson distributed locks, Quartz dynamic scheduling, Thymeleaf + Alpine.js + HTMX server-rendered console, MySQL 9 with a native `vector(1024)` column and local Ollama `bge-m3` embeddings.

---

## Table of Contents

1. [Capability Overview](#1-capability-overview)
2. [Quick Start](#2-quick-start)
3. [Module Structure](#3-module-structure)
4. [Architecture & Workspace](#4-architecture--workspace)
5. [Sub-Agents / Skills / Tools](#5-sub-agents--skills--tools)
6. [Requirements](#6-requirements)
7. [Configuration System](#7-configuration-system)
8. [Database](#8-database)
9. [Authentication](#9-authentication)
10. [AI Chat Pipeline](#10-ai-chat-pipeline)
11. [Email Module](#11-email-module)
12. [To-Do Items](#12-to-do-items)
13. [Knowledge Base & Vector Search](#13-knowledge-base--vector-search)
14. [Task Scheduling Module](#14-task-scheduling-module)
15. [Notification Module](#15-notification-module)
16. [Statistics Dashboard](#16-statistics-dashboard)
17. [API Reference](#17-api-reference)
18. [Frontend Console](#18-frontend-console)
19. [Build & Deploy](#19-build--deploy)
20. [Extension Guide](#20-extension-guide)
21. [Operations & Security Notes](#21-operations--security-notes)
22. [License & Contributing](#22-license--contributing)

---

## 1. Capability Overview

| Capability | Description |
|------------|-------------|
| **AI Chat** | SSE streaming, interruptible, **plan mode** (enter / exit / inspect / write plan), think-trace display, Markdown + code highlighting + HTML preview rendering |
| **Multi-agent orchestration** | Master agent + 19 declarative sub-agents; dispatch is chosen automatically from each sub-agent's `description`; sub-agents can chain (requirement → research → architecture → coding → testing → report) |
| **Collaboration Space** | 7 management views: dashboard / to-do / knowledge base / email / scheduled tasks / schedule logs / agent tasks |
| **Email** | IMAP fetch-to-store + SMTP sending; 🔴 **sending is strictly two-phase** (draft preview first, send only after explicit user confirmation); HTML body rendering supported |
| **Email → Agent pipeline** | Toggleable: each new email can be handed to the agent asynchronously to classify it and **auto-register a to-do** (unattended, never sends mail) |
| **To-Do Items** | CRUD + title fuzzy search + done-status/type filters + slider switch + Markdown content + click title for detail |
| **Knowledge Base** | **Keyword vector index** (bge-m3, 1024-dim) + **two search channels** in the UI (keyword fuzzy / semantic retrieval) |
| **Task Scheduling** | Quartz: dynamic add/update/delete, enable/disable, **run now** (reflective call into any allow-listed bean method), execution logs; split into "common tasks" and "agent tasks" |
| **Agent Tasks** | Push a prompt to the agent on a schedule (unattended mode); runs are logged like any other schedule |
| **Statistics Dashboard** | 4 module cards (knowledge base / email / schedule / to-do) + 7-day trend (tokens / email / schedule / to-do) |
| **Distributed concurrency guard** | Redisson lock protects email fetching; concurrent runs from multiple instances/entry points are skipped |
| **Authentication** | Hutool JWT (HS256) + `@NoAuth`-annotation-driven WebFilter; no Spring Security dependency |

---

## 2. Quick Start

```bash
# 0. Prepare the external config (copy the template, fill in your own passwords / API keys)
cp application.yml.example application.yml

# 1. Compile (JDK 21 required; first run needs network for dependencies)
export JAVA_HOME=/path/to/jdk-21
mvn compile

# 2. Run for development (sai-admin aggregates framework/agent/client/schedule)
mvn -pl sai-admin spring-boot:run

# 3. Open the console
#    http://localhost/   default account is in application.yml under sai.auth.accounts
```

Listens on `server.port=80` by default (override to `8080` etc. in the external `application.yml`).

**Prerequisites**: MySQL (with `vector` type support) + Redis + Ollama (`ollama pull bge-m3`) + an LLM API key.
**Core chat** only needs an LLM API key, but the Collaboration Space (knowledge base / email / to-do / scheduling / dashboard) also needs MySQL and Redis.

---

## 3. Module Structure

Parent POM: `groupId=com.xsl`, `artifactId=SAI`, `packaging=pom`, parent `spring-boot-starter-parent:3.5.14`, JDK 21.

| Module | Role | Description |
|--------|------|-------------|
| `sai-admin` | Bootstrap (the only runnable jar) | `SpringMain` launcher; aggregates the profile configs; `spring-boot-maven-plugin` builds the executable jar |
| `sai-framework` | Infrastructure | R2DBC / Redis clients (including `RedisClient.syncLock`), auth (JWT + `AccountProvider` + `AuthWebFilter`), `BaseEntity` / `Result`, `MailClient` / `MailReceiver`, enums such as `TodoType`, `SpringHolder` |
| `sai-client` | API & business layer | External REST API + business services: chat, conversation, knowledge base, email, to-do, scheduling, notification, statistics |
| `sai-agent` | Agent core | Master agent assembly (`AgentAIConfig`), sub-agent auto-loading (`AgentInit`), custom Tools, keyword extraction (`SemanticRefineHandler`), process tracing (`AgentTraceMiddleware`) |
| `sai-page` | Frontend console | Thymeleaf server rendering + Alpine.js reactivity: chat page plus the 7 Collaboration Space views |
| `sai-schedule` | Task scheduling | Quartz dynamic scheduling engine: `DynamicScheduleManager` + `ScheduleJob` + `TaskInvoker` (reflective invocation + allow-list + timeout) |

**Key dependencies**:

- `sai-framework`: `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`, `org.mariadb:r2dbc-mariadb`, `org.redisson:redisson:4.6.0`, `cn.hutool:hutool-all`, `spring-boot-starter-mail`, `io.netty:netty-resolver-dns-native-macos` (needed to start Netty on macOS)
- `sai-agent`: `io.agentscope:agentscope-harness:2.0.0`, `io.agentscope:agentscope-extensions-model-openai:2.0.0`, `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core`, `org.springframework.ai:spring-ai-openai`
- `sai-page`: `spring-boot-starter-thymeleaf`
- `sai-schedule`: `spring-boot-starter-quartz`

> AgentScope's OpenAI model provider was split out of `agentscope-core` into the standalone extension `agentscope-extensions-model-openai`, so it must be declared explicitly.

---

## 4. Architecture & Workspace

The project follows the AgentScope Harness convention: **workspace directory + declarative builder**, preferring framework built-ins and hand-writing a Tool only when necessary.

### 4.1 System prompt layering (never hardcoded in Java)

| File | Role | How it is injected |
|------|------|--------------------|
| `AGENTS.md` | **Master agent behaviour spec (orchestration layer)** — only four kinds of content: ① identity & orchestration principles ② global conduct rules ③ generic unattended-mode behaviour ④ output conventions and its own file tools | System prompt |
| `SOUL.md` | Persona data disk (name / identity / personality / phrasing), **injected in full every turn**, never rewritten by the background | `agentScope.soulFile` |
| `subagents/*.md` | Sub-agent declarations (frontmatter + body) | Auto-discovered by the framework, which generates the dispatch tool |
| `skills/<name>/SKILL.md` | Skills (domain operation manuals) | Declared by each sub-agent in its frontmatter `skills:` |
| `MEMORY.md` + `memory/` | Long-term memory and journal | Framework consolidation |

> 🔴 **Layering rule**: the master prompt contains **no domain process / field / decision rule / threshold whatsoever** — all of it lives in the corresponding sub-agent or skill. A sub-agent prompt is the **single source of truth** for its domain. The master agent picks a sub-agent by its `description`, so **changing when a sub-agent applies requires updating its `description`**.

### 4.2 Workspace directory (single source of state)

```
.agentscope/workspace/
├── AGENTS.md                  # Master agent behaviour spec (orchestration layer)
├── SOUL.md                    # Persona data disk (injected in full every turn)
├── MEMORY.md                  # Long-term memory (rewritten by consolidation)
├── knowledge/                 # Domain knowledge / RAG
├── skills/                    # Skills: skills/<name>/SKILL.md
├── subagents/*.md             # Sub-agent declarations (filename = agent_id)
├── memory/YYYY-MM-DD.md       # Memory journal
└── <hash>/                    # Session isolation dir (IsolationScope.SESSION)
```

### 4.3 Workspace auto-initialization (`AgentInit`)

`xsl.sai.agent.runner.AgentInit` runs **before** the master agent is built and initializes the workspace from the classpath templates. The required files need no hand-maintained list — `subagents/**`, `skills/**`, and `knowledge/**` are **auto-scanned** (additions and removals take effect on the next startup); the root `AGENTS.md` / `MEMORY.md` / `SOUL.md` are an explicit short list.

| Key | Default | Description |
|-----|---------|-------------|
| `sai.agent.init.enabled` | `true` | Master switch; when `false` initialization is skipped entirely |
| `sai.agent.init.force` | `false` | `false` only **fills in** missing files (runtime memory is preserved); `true` **clears the managed directories** (`subagents/`, `skills/`, `knowledge/`) and fully overwrites |

> ⚠️ **Local development caveat**: the project-root `application.yml` does not override `sai.agent.init.*`, so the classpath defaults apply — if the template sets `force=true`, **the workspace is reset on every startup**.
> ⇒ When changing a sub-agent / skill / master prompt, you **must edit the template under `sai-agent/src/main/resources/` and run `mvn compile` to sync `target/classes/`**; editing only the copy in `.agentscope/workspace/` will be overwritten on the next startup. Deployments usually set `force=false` and must sync the templates separately.

### 4.4 Isolation and scope

`AgentAIConfig` uses `IsolationScope.SESSION`, so every `sessionId` (= `conversationId`) gets its own isolation directory `<hash>/`. Memory does not carry across sessions; `AGENTS.md` / `SOUL.md` / `knowledge/**` are **read-only** to the agent. Evolution should go through the two official channels: **memory** and **skills**.

---

## 5. Sub-Agents / Skills / Tools

### 5.1 Sub-agents (19, under `sai-agent/src/main/resources/subagents/`)

| Category | Sub-agent | Responsibility |
|----------|-----------|----------------|
| Orchestration / general | `Analyst` | General analysis (break a problem down, compare options, give a conclusion) |
| Engineering | `EngineeringAnalyst` | Requirement clarification / research / architecture design / optimisation |
| Engineering | `CodeWriter` | Coding and code style |
| Engineering | `Tester` | Testing and test reports |
| Engineering | `GitAgent` | Git operations and commit conventions |
| Email | `EmailAgent` | **Email hub**: send/receive, two-phase drafts, entry point of the email pipeline |
| Email | `EmailTaskAnalyzer` | Break an email down into an actionable task list |
| To-do | `TodoAgent` | Query / save / update status of to-do data (the only sub-agent that touches `todo_item`) |
| Knowledge | `KnowledgeBaseAgent` | Knowledge base read/write (`save_knowledge` / `query_knowledge`) |
| Data | `DatabaseAgent` | SQL queries and writes |
| System | `ComputerAgent` | Local machine / OS-level operations |
| Web | `BrowserAgent` | Web scraping and search |
| Learning | `StudyAdvisor` / `PaperAdvisor` | Study-plan Q&A / paper topic selection and writing |
| Life | `HealthAdvisor` / `FinanceAdvisor` / `TravelPlanner` / `SocialAdvisor` | Health / personal finance / travel / social |
| Persona | `PersonalityAgent` | Personality and expression style |

> Adding a sub-agent **only requires dropping a `.md` file into `subagents/`** (declaring `tools:` and `skills:` in the frontmatter) — no code change.

### 5.2 Skills (4, under `sai-agent/src/main/resources/skills/`)

| Skill | Attached to | Content |
|-------|-------------|---------|
| `mail` | `EmailAgent` | Email parsing and the **two-phase send** (§4 draft block format: only `recipient / subject / format / body`) |
| `mail-todo` | `EmailAgent` | Email classification (task / errand / meeting / reply) and deciding **whether it must be registered as a to-do** |
| `article-writer` | `KnowledgeBaseAgent` | Long-form writing and knowledge capture |
| `summary` | `Analyst` | Summarisation |

> 🔴 `skills:` is declared **by each sub-agent in its own frontmatter** — these are not the master agent's skills.

### 5.3 Built-in tools (8 classes / 18 tools, registered in `AgentAIConfig#masterToolkit`)

| Tool class | Tool names | Description |
|------------|-----------|-------------|
| `KnowledgeBaseTool` | `save_knowledge` / `query_knowledge` | Knowledge base write and retrieval (`query_knowledge` supports `top_k` / `threshold`) |
| `MailTool` | `send_mail` / `receive_mail` | Send (HTML supported) / fetch inbox |
| `TodoTool` | `query_todo` / `save_todo` / `update_todo_status` | To-do query / save / status update (🔴 returned text contains **no primary key ID**) |
| `SqlTool` | `execute_sql` / `vector_search` / `execute_write` | Read-only SQL / vector search / controlled writes |
| `UtilityTool` | `calculate` / `get_current_time` / `date_diff` / `now_plus_days` / `format_timestamp` | Calculation and time helpers |
| `WebSearchTool` | `web_search` | Web search |
| `WebFetchTool` | `web_fetch` | Fetch page body |
| `ShellTool` | `execute_shell_command` | Allow-listed shell (`sai.agent.shell.enabled` + `allowed-commands`) |

> 🔴 **A new Tool must be registered in `AgentAIConfig#masterToolkit`** — a sub-agent's frontmatter `tools:` entries are resolved by name from that toolkit, so an unregistered tool is unusable.
> 🔴 **The dependency direction is `client → agent`**, so the agent module cannot reach the client's services. A Tool that needs business tables should talk to `R2dbcClient` directly (same database, same tables, consistent with the UI).

---

## 6. Requirements

| Component | Version | Required | Note |
|-----------|---------|----------|------|
| JDK | **21** (mandatory) | yes | Compile and run; newer versions may be incompatible |
| Maven | 3.9+ | yes | Build |
| MySQL | 9.x (needs `vector` support) | Collaboration Space | R2DBC driver `r2dbc-mariadb`; `knowledge_chunk.embedding_vec` uses the native `vector(1024)` |
| Redis | 7+ | Collaboration Space | Redisson distributed lock; with `fail-fast:false` an unreachable Redis does not block startup |
| Ollama | any | Vector search | Local `bge-m3`: `ollama pull bge-m3` (1024 dims) |
| LLM API | — | required for chat | Configure `apiBase` / `apiKey` / `modelName` under `agentScope.models` |
| SMTP + IMAP | — | email features | e.g. QQ Mail (authorization code, not the login password) |

---

## 7. Configuration System

Configuration is split by profile; `sai-admin/src/main/resources/application.yml` activates them.

| File | Module | Content |
|------|--------|---------|
| `application-core.yml` | global | Common Spring settings |
| `application-data.yml` | global | Datasource / Redis |
| `application-api.yml` | global | API-layer common settings |
| `application-framework.yml` | `sai-framework` | Email send/receive parameters (`spring.mail.receive.*`), auth accounts, `Result` related |
| `application-client.yml` | `sai-client` | Business toggles (e.g. the email pipeline's `dispatch-to-agent`) |
| `application-agent.yml` | `sai-agent` | Model, embedding, workspace, Shell/MCP/knowledge toggles, orchestration parameters |
| `application-page.yml` | `sai-page` | Thymeleaf (`cache: true`), static resource mapping |
| `application-schedule.yml` | `sai-schedule` | Quartz thread pool, `allowed-beans` allow-list |

### 7.1 External override (required reading for deployment)

The project-root `application.yml` is the **external override file**: Spring Boot uses it to override same-named classpath keys **key by key**, and keys that exist only inside the jar **still take effect** (so the outer file only needs the values you want to change).

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
    maxTokens: 4000          # thinking and the answer share this budget; too small truncates the answer
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

### 7.2 Environment variables

| Variable | Purpose |
|----------|---------|
| `SAI_DB_PASSWORD` | Database password |
| `SAI_REDIS_PASSWORD` | Redis password |
| `SAI_LLM_API_KEY` | LLM API key |
| `SAI_MAIL_PASSWORD` | Mailbox authorization code |
| `SAI_JWT_SECRET` | JWT signing secret (a long random string) |
| `SAI_ADMIN_PASSWORD` | Admin console password |

### 7.3 Common toggles

| Key | Default | Description |
|-----|---------|-------------|
| `spring.mail.receive.dispatch-to-agent` | `false` | Whether fetched email is handed to the agent (see §11.3) |
| `spring.mail.receive.agent-content-chars` | `4000` | Body length limit submitted to the agent |
| `spring.mail.receive.agent-timeout-minutes` | `5` | Per-email agent timeout (logged only) |
| `sai.agent.init.enabled` / `force` | `true` / — | Workspace initialization (see §4.3) |
| `sai.agent.shell.enabled` / `allowed-commands` | `true` / allow-list | Shell tool |
| `sai.agent.mcp.enabled` | `false` | MCP integration |
| `sai.agent.knowledge.enabled` | `true` | Knowledge retrieval capability |
| `agentScope.harness.maxIters` | `20` | Max iterations per master-agent turn; too small truncates long tasks |
| `spring.thymeleaf.cache` | `true` | 🔴 Page template cache — template changes **require a restart** |

---

## 8. Database

Database name `sai` (MySQL 9.x), **10 tables** in total:

| Table | Purpose |
|-------|---------|
| `client_conversation` | Conversations (title, pinned, update time) |
| `client_message` | Messages (including token statistics, aggregated by the dashboard) |
| `agent_memory` | Agent memory |
| `knowledge_base` | Knowledge base body entries |
| `knowledge_chunk` | **Keyword vector index** (one keyword per row + `embedding_vec vector(1024)`) |
| `received_email` | Received and sent share one table (`direction` 0 = received / 1 = sent, includes `html_content`) |
| `todo_item` | To-do items (`title` / `type` / `content` / `done`) |
| `schedule_task` | Common scheduled task definitions |
| `schedule_log` | Schedule execution log (shared by common and agent tasks) |
| `notify_message` | In-app notifications |

### 8.1 Schema creation and initialization

- The full DDL is in [`sql/sai.sql`](./sql/sai.sql).
- 🔴 `SchemaInitRunner` covers only **7 fixed tables** and does **not** create new business tables such as `todo_item` ⇒ **the first deployment must import `sql/sai.sql` manually**, and any new business table needs a manual `CREATE TABLE` whose DDL is written back into `sql/sai.sql`.
- 🔴 **The `tinyint(1)` trap**: MySQL's `tinyint(1)` is read by R2DBC as **`Boolean`**.
  - Entity field is `Integer` ⇒ the DDL must use **`tinyint` (without a length)**;
  - Entity field is `Boolean` ⇒ only then use `tinyint(1)`.
  - Otherwise you get `No converter found capable of converting from type [java.lang.Boolean] to type [java.lang.Integer]`, with the confusing symptom "**writes succeed, queries return 500**". (See the comments on `received_email.direction` and `todo_item.done`.)

### 8.2 Time and timezone

The database container runs in **UTC** while the display layer formats local time (an 8-hour difference — keep this in mind when investigating time-related issues).

---

## 9. Authentication

- **Login**: `POST /api/auth/login` (`{username, password}`) → returns `{token, username, displayName}`. The JWT is signed with **HS256** via Hutool `JWTUtil` (secret `sai.auth.jwt-secret`) and expires after 24 hours by default.
- **Accounts** come from the `sai.auth.accounts` list (`AccountProvider`); multiple accounts are supported and nothing is persisted.
- **Bypass**: an `@NoAuth`-annotation-driven `AuthWebFilter` (`@Order(HIGHEST_PRECEDENCE)`) — there is no path allow-list, only the annotation. Currently `login` / `logout` and the page controller `PageController` are open.
- **Frontend**: `app.js#api()` attaches the `Authorization: <token>` header to every request.
- ⚠️ **There is no role model and no token revocation** (logging out only clears the token client-side) — this is a deliberately lightweight, intranet-oriented design.

---

## 10. AI Chat Pipeline

| Endpoint | Description |
|----------|-------------|
| `POST /agent/chat` | **SSE stream** (`text/event-stream`, `multipart/form-data`: `data` = `ChatDTO`, optional `files`). The server pushes `Result` chunks |
| `POST /agent/chat/{conversationId}/stop` | Interrupt the current generation |
| `POST /agent/plan/enter` / `exit` / `GET /plan/status` / `POST /plan/write` | Plan mode: enter (read-only research, produce a plan) / exit / inspect status / persist the plan |

- The chat page **intentionally has no timeout** so long tasks can stream indefinitely.
- Message text, plans, and thinking traces are all persisted to `client_message` / `client_conversation`.
- Observability: `AgentTraceMiddleware` emits four categories of tagged logs; cross-callback state is keyed by `` `sessionId@AgentName` ``.
- ⚠️ `agentScope.harness.maxIters` caps iterations per turn; too small a value makes long tasks **stop halfway** (currently 20).
- ⚠️ **Empty replies are not persisted**; to debug a "lost reply", look for conversations with two adjacent user messages and no assistant message between them.

---

## 11. Email Module

`received_email` stores **received and sent in the same table**, distinguished by `direction` (`0` received / `1` sent).

### 11.1 Receiving (IMAP)

- Entry points: `POST /api/email/fetch` (the "Fetch now" button) and a scheduled task (every 5 minutes by default, see §14).
- Flow: fetch inbox → deduplicate by `message_id` (unique index `uk_received_email_msgid`) → model-generated summary → persist (also storing the HTML body in `html_content`).
- 🔴 **Distributed lock**: `fetchAndStore()` holds a Redisson lock for its whole duration (key `sai:lock:email:fetch`, `tryLock()` with no wait + watchdog renewal). **With concurrent entry points or multiple instances, whoever fails to acquire the lock returns 0 immediately** — note that "returns 0" means **this round did not fetch**, not that the inbox had nothing new.

### 11.2 Sending (SMTP) — strictly two-phase

🔴 **Sending mail is always two-phase**: produce a **draft preview** first and send only after the user **explicitly confirms**.

- The draft shows **exactly four items**: `recipient / subject / format / body` (no heading line, no byte-count hint, no confirmation wording).
- **Not a confirmation**: the user's original "send an email for me"; vague replies such as "mm", "ok", "got it". If the content changed, preview again.
- The rule lives in three places, and **changing only one has no effect**: `subagents/EmailAgent.md` (the sending rule), `skills/mail/SKILL.md` §4 (draft block format), `AGENTS.md` (the master agent's gatekeeping).
- A successful send is recorded through **`EmailSentEvent`**; 🔴 **`EmailEventBus` is never published** on that path (this prevents the "auto-reply → stored → treated as a new email" infinite loop).

### 11.3 Email → Agent pipeline (unattended)

Toggle: `spring.mail.receive.dispatch-to-agent` (default `false`).

When enabled, every **newly stored** email is submitted to the agent **asynchronously** by `MailAgentDispatcher` with a `[邮件流水线-无人值守模式]` prefix (`subscribeOn(boundedElastic).subscribe`, so neither the fetch endpoint nor the scheduled task is blocked):

1. `EmailAgent` applies the `mail-todo` skill to classify the email (task / errand / meeting / reply) and decide whether it should be registered as a to-do;
2. if it should, `TodoAgent` saves it (a duplicate check runs first);
3. **no email is ever sent**.

- 🔴 Each email uses an **independent session** `email-bot-<emailId>`: the framework keeps only one background loop per session, so sharing a session would make concurrent emails interrupt each other.
- Because this is fire-and-forget, when the fetch endpoint reports "N new" the to-do may not exist yet (the agent is still running). **Its visible output is the new entry in the to-do list.**

---

## 12. To-Do Items

Table `todo_item`, endpoint prefix `/api/todo`.

| Field | Type | Description |
|-------|------|-------------|
| `title` | `varchar(500)` | Title |
| `type` | **`tinyint`** | Type code: `1` task / `2` errand / `3` meeting / `4` reply |
| `content` | `longtext` | To-do content (**Markdown**, rendered by the frontend) |
| `done` | **`tinyint`** | `0` open / `1` done |

- **The type mapping** lives in `xsl.sai.framework.enums.TodoType` in `sai-framework` (shared by client and agent), exposing `parse(Object)` (accepts both numbers and Chinese labels) / `normalize()` / `labelOf()`.
- **Column order**: title / content / type / status / update time / actions.
- **Search and filters**: `GET /page` supports three optional conditions (blank = no filter) — `keyword` (**title only**, fuzzy), `done` (`0` / `1`), `type`; it returns `{list, total (filtered hits), active (**global** open count, unaffected by filters)}`.
- **Interaction**: clicking the **title** opens a detail modal (reusing the knowledge base detail structure); the **slider switch** in the list toggles the done state and writes straight to the database (`POST /{id}/toggle`).

---

## 13. Knowledge Base & Vector Search

`knowledge_base` holds the body entries and `knowledge_chunk` holds the **keyword vector index** (one keyword per row, each embedded separately).

### 13.1 Writing (keyword extraction)

Long text (> 1000 characters) is first split into chunks; each chunk is mined for keywords, then the results are merged and deduplicated → each keyword is embedded into a 1024-dim vector via Ollama `bge-m3` → written to `knowledge_chunk`.
The keyword extraction prompt lives in `SemanticRefineHandler.KEYWORD_SYSTEM` and covers topics / concepts / tech stack / feature modules / actions / usage scenarios (**not limited to named entities such as people or places**).

### 13.2 Two search channels in the UI

| Mode | Endpoint | Description |
|------|----------|-------------|
| **Search** (keyword) | `GET /api/knowledge/page?keyword=` | Fuzzy keyword search: a hit on `content LIKE` **or** the entry's extracted `keyword LIKE`, **paginated** |
| **Retrieve** (semantic) | `GET /api/knowledge/semantic?text=&topK=5&threshold=` | Vector recall ordered by similarity, not paginated |

- The **`.search-mode-switch` slider** in the toolbar switches between the two modes; in retrieve mode the "status" column header becomes "similarity" and is centred.
- `threshold` is best kept at **0.3 – 0.5**: 0.8 or above usually returns nothing.

### 13.3 Related endpoints

`POST /api/knowledge` (save/update), `POST /api/knowledge/extract-keywords` (extract only, no persistence — used by the modal preview), `GET /api/knowledge/{id}`, `DELETE /api/knowledge/{id}`.

> ⚠️ Known pitfall: `knowledge_chunk.keyword` uses a different collation from the other tables (`utf8mb4_unicode_ci` vs `utf8mb4_0900_ai_ci`). Comparing across tables with a **user variable** in a SQL client raises `1267 Illegal mix of collations`, but the **driver-side `?` parameter does not** (it is a false alarm — do not change the schema for it). Validate such SQL with `PREPARE/EXECUTE ... USING`.

---

## 14. Task Scheduling Module

Built on **Quartz**, supporting runtime add/update/delete, enable/disable, and **run now**, with every run written to `schedule_log`.

### 14.1 The scheduling chain

```
① Startup   DynamicScheduleManager#afterPropertiesSet
            loads tasks with status=1 and not deleted -> scheduler.scheduleJob(jobDetail, cronTrigger)
            JobDataMap carries taskId / taskName / invokeTarget / groupName / concurrent / timeout

② Trigger   ScheduleJob#execute (Quartz Job)
            concurrent=0 -> a static atomic lock prevents re-entry; an occupied slot logs a SKIP entry
            submitted to a thread pool, with Future.get(timeout) as the timeout guard

③ Reflect   TaskInvoker#invoke("beanName.method()")
            validates against the sai.schedule.allowed-beans allow-list
            invokes the no-arg method reflectively; a returned Mono goes through unwrapReactive() (blocks the background thread only)

④ Business  the target service method
```

### 14.2 Two kinds of tasks

| Kind | Description |
|------|-------------|
| **Common task** | `invoke_target` looks like `emailReceiveService.fetchAndStore()`, invoking any allow-listed bean method reflectively |
| **Agent task** | Pushes a prompt to the agent on a schedule (`[定时任务-无人值守模式]` prefix); runs autonomously, asks no questions, and produces an execution summary |

### 14.3 Configuration and caveats

- 🔴 A service implementation referenced by the schedule table **must declare an explicit bean name**: `@Service("emailReceiveService")` (the interface name with a lowercase first letter), otherwise the reflective lookup fails.
- 🔴 List-valued configuration **cannot use `@Value`** (it will not be read) — use `@ConfigurationProperties` (as the `allowed-beans` allow-list does).
- Task **seed data is not in `sql/sai.sql`**; insert it manually per `DEPLOY.md` (e.g. `email-fetch` / `emailReceiveService.fetchAndStore()` / `0 0/5 * * * ?`), otherwise no scheduled task runs after startup.

---

## 15. Notification Module

`notify_message` provides in-app notifications (no dedicated page yet; available for frontend polling / future extension):

| Endpoint | Description |
|----------|-------------|
| `GET /api/notify/list?limit=20` | Notification list |
| `GET /api/notify/unread-count` | Unread count |
| `GET /api/notify/sync?afterId=0` | Incremental fetch |
| `POST /api/notify/read/{id}` / `POST /api/notify/read-all` | Mark one / all as read |
| `POST /api/notify/test?title=&content=` | Generate a test notification |

---

## 16. Statistics Dashboard

`GET /api/stats/dashboard` (`StatsServiceImpl`) returns:

- **4 module cards**: knowledge base (entries + keywords), email (inbox / sent / pending summary / today), scheduling (runs today / total / success rate), to-do (open / total / created today);
- **7-day trend**: token consumption, email traffic, schedule runs, new to-dos (4 small charts);
- the model name, plus today's and cumulative token counts.

> Trend semantics: email and to-do are aggregated by `create_time` (measuring **daily additions**), while scheduling uses the run count from `schedule_log`.

---

## 17. API Reference

Unified prefix `/api` (chat excepted); every response body is a `Result`:

```json
{ "code": 200, "message": "success", "data": { } }
```

> 🔴 **`Result` always returns HTTP 200**; errors are expressed only in the body's `code` (`200` = success). Frontend write operations (save/delete) must inspect the full `Result` and check `code`, otherwise **a failure will be reported as a success**.

### 17.1 Auth `/api/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/login` | `@NoAuth` | Log in, returns a JWT |
| POST | `/logout` | `@NoAuth` | Log out (the frontend clears the token) |
| GET | `/me` | required | Current user info |

### 17.2 Chat `/agent`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/chat` | SSE streaming chat (multipart: `data` + `files`) |
| POST | `/chat/{conversationId}/stop` | Interrupt generation |
| POST | `/plan/enter` | Enter plan mode |
| POST | `/plan/exit` | Exit plan mode |
| GET | `/plan/status` | Plan-mode status |
| POST | `/plan/write` | Write the plan file |

### 17.3 Conversation `/api/conversation`

`GET /list`, `POST /` (create), `GET /{id}/messages`, `PUT /rename`, `PUT /pin`, `DELETE /{id}`

### 17.4 Knowledge base `/api/knowledge`

`POST /` (save/update), `POST /extract-keywords`, `GET /page` (`pageNum` / `pageSize` / `keyword`), `GET /{id}`, `DELETE /{id}`, `GET /semantic` (`text` / `topK` / `threshold`)

### 17.5 Email `/api/email`

`GET /list` (`keyword` / `direction` / paging), `GET /{id}`, `DELETE /{id}`, `POST /fetch`

### 17.6 To-do `/api/todo`

`POST /` (save/update), `GET /page` (`keyword` / `done` / `type`), `GET /{id}`, `DELETE /{id}`, `POST /{id}/toggle`

### 17.7 Scheduled tasks `/api/schedule`

`GET /page`, `POST /`, `PUT /`, `POST /{id}/toggle`, `POST /{id}/run` (run now), `DELETE /{id}`, `GET /{id}`, `GET /{id}/logs`, `GET /beans` (the selectable bean allow-list), `GET /logs`

### 17.8 Agent tasks `/api/agent-schedule`

`GET /page`, `POST /`, `PUT /`, `POST /{id}/toggle`, `POST /{id}/run`, `DELETE /{id}`, `GET /{id}`, `GET /{id}/logs`, `GET /logs`

### 17.9 Notification `/api/notify`

`GET /list`, `GET /unread-count`, `GET /sync`, `POST /read/{id}`, `POST /read-all`, `POST /test`

### 17.10 Statistics `/api/stats`

`GET /dashboard`

### 17.11 Pages `/`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Main console (`index.html`) |
| GET | `/view/{name}` | Fragment (gated by `PageController#FRAGMENTS`) |

> **Paging convention**: uniformly `pageNum` / `pageSize` (`/api/schedule` and `/api/agent-schedule` use `page` / `size` on some endpoints — refer to each controller signature).

---

## 18. Frontend Console

Stack: **Thymeleaf server rendering + Alpine.js reactivity + HTMX fragment loading + marked / DOMPurify / highlight.js** (all third-party libraries are localized, no external network dependency).

### 18.1 Navigation structure

```
AI Chat                            (mode=chat)
Collaboration Space
  🏠 Dashboard
  To-do
    ✅ To-Do Items
  Knowledge Base
    📚 Knowledge Base
  Email
    ✉️ Email
  Automation
    ⏰ Scheduled Tasks
    📜 Schedule Logs
    🤖 Agent Tasks
```

### 18.2 Key conventions (read before touching the frontend)

| Convention | Description |
|------------|-------------|
| **A new page needs four fixed changes** | ① `templates/fragments/<name>.html` ② a sidebar `snav-link` + a `view-pane` in `index.html` ③ the `PageController#FRAGMENTS` allow-list ④ the `go()` branch + the `restoreView()` view array in `app.js` |
| 🔴 **Static asset version** | Changing `app.js` / `app.css` **requires bumping `?v=N` in `index.html`** (otherwise the browser cache is not refreshed); a template-only change does not |
| 🔴 **Template cache** | `spring.thymeleaf.cache: true` ⇒ a template change **requires an application restart**; refreshing the page will not help |
| **Hide the header when empty** | Lists uniformly use `<table class="tbl" x-show="<dataArray>.length">`; the empty state `.kb-empty` sits **as a sibling** of the table inside the container (the container already has a border and background — do not wrap it again) |
| **Markdown rendering** | Pre-render once inside `load*()` as `html: this.md(content)` and render with `x-html`; **never call `md()` directly in a template** (Alpine would re-run it on every recomputation) |
| 🔴 **Top-level keys must be unique** | `app.js` is a single object literal, so **a later key silently overwrites an earlier one**. A filter field colliding with a method name once caused intermittent 400 responses |
| 🔴 **Whitelist query strings** | Parameters built on the frontend must be whitelisted and coerced with `String()` (e.g. `done` accepts only `'0'` / `'1'`), and the `catch` branch should log the real URL |
| **Reuse existing styles** | Toolbars use `.kb-toolbar` / `.kb-search-box` / `.kb-select`; detail modals use `.kb-detail` / `.modal-card.wide`; switches use `.search-mode-switch` / `.todo-switch` |

> 🛠 The repo ships a static self-check script `.workbuddy/scripts/page_static_check.py` (duplicate top-level keys / dangling template references / tag balance) — **run it after frontend changes, before restarting**.

---

## 19. Build & Deploy

```bash
# Compile (JDK 21 required)
export JAVA_HOME=/path/to/jdk-21
mvn clean compile

# Run for development
mvn -pl sai-admin spring-boot:run

# Package (executable jar)
mvn clean package -DskipTests
java -jar sai-admin/target/sai-admin-*.jar
```

**Docker deployment** (including MySQL / Redis / the application) is covered in [`DEPLOY.md`](./DEPLOY.md); Ollama details are in [`deploy-ollama-bge-m3.md`](./deploy-ollama-bge-m3.md).

Deployment quick notes:

- Mount the external `application.yml` into the container (it overrides the jar config key by key);
- The first deployment must import `sql/sai.sql` manually (`SchemaInitRunner` creates only 7 fixed tables);
- Importing SQL must use `--default-character-set=utf8mb4`, otherwise Chinese text is double-encoded and garbled;
- Vector capability depends on a MySQL image that supports the `vector` type;
- Ollama must listen on `0.0.0.0:11434` to be reachable from containers.

---

## 20. Extension Guide

| Goal | How |
|------|-----|
| **Add a sub-agent** | Drop a `.md` into `sai-agent/src/main/resources/subagents/` (frontmatter: `description` / `tools:` / `skills:` / `steps:`); `AgentInit` discovers it automatically. 🔴 `description` determines whether the master agent routes to it |
| **Add a skill** | Add `sai-agent/src/main/resources/skills/<name>/SKILL.md` and declare it in the `skills:` frontmatter of the sub-agent that uses it |
| **Add a Tool** | Write a `@Component` class with `@Tool` / `@ToolParam` methods and **register it in `AgentAIConfig#masterToolkit`**; to reach business tables, use `R2dbcClient` directly (the agent module cannot reach client services) |
| **Add a page** | See §18.2 "A new page needs four fixed changes" |
| **Add a business table** | Manual `CREATE TABLE` + write the DDL back into `sql/sai.sql` + an entity with `@Table`. 🔴 For a 0/1 flag whose field is `Integer`, the DDL must use `tinyint` (without a length) |
| **Add a dashboard module** | Add fields to `DashboardVO` → add statistics and daily aggregation in `StatsServiceImpl` (`Mono.zip` has an 8-tuple limit) → add a card and a mini chart in `home.html` → extend `dash` / `dashCls` / `dashLabel` in `app.js` → add the four theme-colour rules in `app.css` |
| **Add a scheduled task** | Insert a row into `schedule_task` (`invoke_target` pointing at an allow-listed bean method), or create it from the "Scheduled Tasks" page |

> ⚠️ After changing any template under `sai-agent/src/main/resources/` (sub-agent / skill / prompt) you **must run `mvn compile` to sync `target/classes/`**, otherwise the runtime keeps reading the old copy.

---

## 21. Operations & Security Notes

1. **Credential management**: database / Redis / LLM key / mailbox authorization code / admin password are all injected through **environment variables** (see §7.2); the template is `application.yml.example`.
   ⚠️ The project-root `application.yml` is in `.gitignore`, but **if it was ever committed and is still tracked, `.gitignore` will not untrack it** — before going live, run `git rm --cached application.yml`, **rotate any exposed keys**, and scrub history as needed.
2. **Email two-phase rule**: any automated path (scheduled task / email pipeline) **only produces drafts and never sends automatically**.
3. **AI replies contain no internal IDs**: the to-do tools deliberately return text **without the primary key ID** (users identify items by title + status).
4. **`Result` always returns HTTP 200**: frontend write operations must check `code`, not just the HTTP status.
5. **Template cache**: restart the application after changing a Thymeleaf template.
6. **Static asset versions**: bump `?v=N` whenever you change `app.js` / `app.css`.
7. **Concurrency guard**: email fetching is protected by a Redisson lock; if Redis is unavailable the lock **degrades to lock-free execution** (duplicate fetches are caught by the `message_id` unique index).
8. **Shell tool**: `sai.agent.shell.enabled` is a genuinely dangerous capability — disable it in production or narrow the `allowed-commands` allow-list.
9. **Log directory**: application logs are at `log/YYYY_M/NN.txt` (**not** `logs/`); the `【REQUEST】` line contains the full query parameters, so check it first when debugging frontend parameter issues.
10. **Timezone**: the database container runs in UTC, 8 hours behind local time.

---

## 22. License & Contributing

This project is licensed under the [Apache License 2.0](./LICENSE).

### Preparing configuration for the first deployment

1. Copy `application.yml.example` to the project-root `application.yml`;
2. Fill in the database, Redis, and LLM API key (environment variables recommended);
3. Import `sql/sai.sql` (must use `--default-character-set=utf8mb4`);
4. Insert the default scheduled task per `DEPLOY.md`;
5. Start the application and open `http://localhost/` to log in.

### Contributing

- Before committing, make sure: `mvn clean compile` passes (JDK 21); after frontend changes you have run `.workbuddy/scripts/page_static_check.py`; any new business table has been synced to `sql/sai.sql`.
- Prompt changes (sub-agents / skills) must follow the **layering rule** in §4.1: domain details belong in the corresponding sub-agent or skill, not in the master prompt.

### Security disclosure

If you find a security issue (especially credential exposure, injection, or privilege escalation), please do not open a public issue — contact the maintainers through the private channel provided in the repository first.
