# Helm Production Roadmap

本文档是 Helm 后续开发的唯一长期计划与进度跟踪入口。它聚焦 Helm 自身目标：把当前 Java 21 Agent Harness MVP 基座推进到可用于生产环境的框架能力。

## 1. 当前状态

Helm 当前是 Java 21 Maven 多模块项目（namespace `io.agent.helm`），22 个模块全部落地，`mvn verify` 全绿（809 测试）。已具备的生产能力：

- `helm-core`：公开 API/SPI、消息模型、结构化错误（`HelmException` cause 链 + `ErrorCode` 注册表）、tool/workflow/sandbox、Store 子接口（`SessionStore`/`OperationStore`/`WorkflowRunStore`/`EventStore`）、`MemoryStore` SPI、JsonSchema 扩展（Map/enum/Optional/UUID/Instant/BigDecimal/Set）、`ToolContext`。
- `helm-agent-engine`：agent loop、stream 聚合、tool-call 执行、engine events、token usage 聚合、`ContextOverflow` 分类、`EngineException` 层级。
- `helm-runtime`：agent/workflow orchestration、`FakeProvider`、`InMemoryRuntimeStore`、event taxonomy + 脱敏、inspection API、`OperationHandle`/`dispatch` admission、session/memory 管理、`maxSessionMessages` 历史裁剪、`AgentRuntime` AutoCloseable；durable `WorkQueue` + claim/lease/recovery @Preview；streaming `promptStream` @Preview；`RateLimiter`/`HelmAuthorizer` admission。
- providers：`helm-provider-openai`/`helm-provider-anthropic` 真实适配 + mock contract tests。
- sandbox：`helm-sandbox-local` 进程级 sandbox（默认禁用 shell、env allowlist、进程树 kill、TOCTOU 缓解）。
- HTTP/客户端：`helm-http-core`/`helm-http-servlet`/`helm-client` —— DTO/路由/servlet 适配、`HelmSecurityContext`/`HelmAuthorizer`、body 上限、routePrefix、authz 信封、SSE 错误上报、优雅 shutdown；client SDK AutoCloseable、增量流式、错误码保留。
- CLI：`helm-cli`（`run`/`dev`/`operations`/`runs`/`run-detail`，默认 bind `127.0.0.1`）。
- Spring：`helm-spring-boot-starter`（auto-config、bean discovery、properties、conditional HTTP）。
- 持久化：`helm-persistence-jdbc`（`JdbcRuntimeStore`/`JdbcMemoryStore`、Flyway、OCC、幂等事件、span TTL、LIKE 转义）。
- 可观测性：`helm-observability-logging` + `helm-observability-opentelemetry`（Observer SPI、结构化日志、metrics + tracing、`@Redact`/`ContentCaptureLevel`）。
- memory 语义：`helm-memory-semantic`（`SemanticMemoryStore`/`EmbeddingProvider`/`EmbeddingStore` SPI + InMemory/Fake 实现）。
- 测试与发布：`helm-runtime-testkit`（fixtures + smoke test）、`helm-bom`、`examples/coding-workflow` + `examples/memory-session-example`。

最近验证命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify
```

验证结果：BUILD SUCCESS，809 个测试通过，Spotless 通过。

当前结论：Helm 生产化基座已就位（M0–M10 完成），仅 durable scale（M11，partial）与 remote/container sandbox 待补；首个发布版本定为 0.1.0。

## 2. 生产化设计原则

1. **Core-first**：核心 runtime 不依赖 Spring、Servlet、CLI、provider SDK、JDBC、logging adapter。
2. **Contract-first**：所有可替换能力先定义稳定 SPI 与合约测试，再实现具体 adapter。
3. **Agent 与 Workflow 分离**：Agent 是持续会话；Workflow 是有限任务。不要混用 operation id、dispatch id、workflow run id。
4. **Admission 优先**：prompt、dispatch、workflow invoke 都应先形成可检查的 operation/run 记录，再执行或排队。
5. **事件优先**：HTTP、CLI、SDK、日志、metrics、tracing 都应基于同一套 runtime event taxonomy。
6. **安全默认**：HTTP 默认关闭，local shell 默认关闭，provider credentials 只服务端读取，events/logs 默认脱敏。
7. **应用拥有业务权限**：Helm 提供 authorizer/security context 扩展点；agent instance id 不代表授权。
8. **窄工具边界**：Tool 暴露最小业务能力，不暴露宽泛客户端、凭据或任意外部目标选择权。
9. **本地沙箱不是生产隔离**：Local sandbox 只降低误操作风险；生产隔离依赖 container、VM 或 remote sandbox adapter。
10. **可验证发布**：每个模块必须有测试、文档、示例和发布检查项，不能只靠设计文档。

## 3. 目标模块图

```text
helm/
  helm-core/
  helm-agent-engine/
  helm-runtime/
  helm-runtime-testkit/
  helm-provider-openai/
  helm-provider-anthropic/
  helm-persistence-jdbc/
  helm-sandbox-local/
  helm-http-core/
  helm-http-servlet/
  helm-client/
  helm-cli/
  helm-spring-boot-starter/
  helm-observability-logging/
  helm-observability-opentelemetry/        # post-preview
  examples/
  docs/
```

依赖守则：

- `helm-core` 不依赖 runtime、engine、HTTP、CLI、Spring、provider SDK、JDBC、logging。
- `helm-agent-engine` 只依赖 `helm-core`。
- `helm-runtime` 依赖 `helm-core` + `helm-agent-engine`。
- Adapter 模块只依赖稳定 API/SPI，不能依赖 runtime internals。
- Testkit 只提供合约测试和 fixture，不成为生产依赖。

### 3.1 生产组件缺口分析（2026-07-05）

对照生产环境 Agent 框架所需组件的检查结论：

| 组件 | 检查前状态 | 结论与动作 |
| --- | --- | --- |
| CLI | 已有 `helm-cli`（run/dev/operations/runs/run-detail） | 已具备 |
| HTTP / Servlet / Spring Boot | 已有 `helm-http-core`、`helm-http-servlet`、`helm-spring-boot-starter` | 已具备 |
| 持久化 | 已有 `RuntimeStore` SPI + `helm-persistence-jdbc` | 已具备 |
| Provider / Sandbox / Skill | 已有 OpenAI/Anthropic provider、local sandbox、skill loader | 已具备 |
| 可观测性 | 已有 `RuntimeEventObserver` + `helm-observability-logging` | 已具备（metrics/OTel 见 M9） |
| **Memory 管理（长期记忆）** | 缺失 | 新增 `MemoryStore` SPI（`helm-core`）、`InMemoryMemoryStore` + 内置 `save_memory` tool（`helm-runtime`）、`JdbcMemoryStore` + `V2__memory` 迁移（`helm-persistence-jdbc`）；记忆按 `agentName:instanceId` scope 存储，prompt 时自动注入 instructions |
| **Session 管理（生命周期）** | 只有隐式会话持久化 | `RuntimeStore` 新增 `listSessions`/`deleteSession`；`AgentRuntime` 新增 `listSessions`/`getSession`/`resetSession` |
| **上下文控制（history compaction）** | 历史无限增长 | `AgentRuntime.Builder.maxSessionMessages` 裁剪历史，裁剪窗口从 user message 开始，避免孤立 tool result |
| 验证示例 | 缺少覆盖 memory/session 的示例 | 新增 `examples/memory-session-example`：客服助手场景端到端验证 memory、session 恢复、session 管理、tool 调用与 operation 检查 |

截至 2026-07-05，前述 11 个生产化组件均已具备基础实现（✓ basic）：流式 API（`PromptStreamEvent`/`promptStream` @Preview）、engine hardening、JsonSchema 扩展、memory 语义检索（`SemanticMemoryStore` SPI + `InMemoryEmbeddingStore`/`FakeEmbeddingProvider`）、authorizer（`HelmSecurityContext`/`HelmAuthorizer`）、HTTP client SDK（`helm-client`）、rate limiting（`RateLimiter` 基础）、metrics/OTel（`helm-observability-opentelemetry`）、API governance、release engineering 均已落地。仍待完成：durable scale 🟡 部分实现（`WorkQueue` + claim/lease/recovery 已完成；`TurnJournal` 仅 SPI 占位、stream-chunk 恢复 / durable cancellation / provider routing-fallback / remote-container sandbox 未做）；remote/container sandbox ◯ 未实现；真实 vector store 适配（当前 `EmbeddingStore` 仅 InMemory/Fake）。

这些留待后续的能力已逐组件给出可评审设计方案，见 [`docs/design/`](design/) 目录（共 11 篇：流式 API、engine hardening、JsonSchema 扩展、memory 语义检索、authorizer、HTTP client SDK、rate limiting、metrics/OTel、durable scale、release engineering、API governance）。

## 4. Milestone 总览

状态取值：`proposed`、`active`、`blocked`、`complete`。

| Milestone | 状态 | 优先级 | 目标 | 依赖 |
| --- | --- | --- | --- | --- |
| M0 API 边界与模块策略 | complete | P0 | 冻结核心 public/internal 边界、验证 builder/API 合约 | 当前 MVP 基座 |
| M1 Runtime lifecycle / inspection / events | complete | P0 | operation/run 状态、inspection API、event taxonomy、dispatch admission | M0 |
| M2 Contract-first persistence / JDBC | complete | P0 | Store SPI、testkit、JDBC、migration、version conflict | M1 |
| M3 Engine hardening / tool validation / context | complete | P0 | engine events、tool validation、usage、structured engine errors | M1 |
| M4 Real providers | complete | P0 | OpenAI/Anthropic provider、mock contract tests、credential redaction | M1/M3 |
| M5 Skills and sandbox | complete | P0/P1 | skill loader、InMemorySandbox、LocalSandbox、安全策略 | M0/M1 |
| M6 HTTP core / servlet / client | complete | P0/P1 | HTTP DTO/handler、Servlet adapter、authorizer、client SDK | M1/M2 |
| M7 CLI and developer experience | complete | P1 | dev/run/inspect/list commands、JSON/human output | M6 |
| M8 Spring Boot starter | complete | P1 | auto-config、bean discovery、properties、示例 | M6/M2 |
| M9 Observability and operations | complete | P1 | observer SPI、logging、metrics、OpenTelemetry 预备 | M1/M3 |
| M10 Docs / blueprints / release engineering | complete | P0/P1 | CI、license、changelog、Maven publish、blueprints、examples | 横向贯穿 |
| M11 Post-GA durable scale | active | P2 | async workers、queue、lease/recovery、remote sandbox、routing/fallback | M1-M10 |

## 5. Milestone 详细计划

### M0：API 边界与模块策略

目标：在新增 adapter 前稳定核心边界，避免后续模块倒逼 public API 失控。

主要文件：

- `pom.xml`
- `helm-core/src/main/java/io/agent/helm/core/agent/AgentConfig.java`
- `helm-core/src/main/java/io/agent/helm/core/store/RuntimeStore.java`
- `docs/helm-mvp-design.md`

交付：

- [x] 确认 Maven groupId/artifact 命名策略。
- [ ] 写明 public package、SPI package、internal package 规则。
- [x] `AgentConfig` builder 增加必填校验：model、instructions 默认、duplicate tool name、unsafe sandbox 默认。
- [ ] 明确 pre-1.0 API compatibility policy。
- [x] 决定 `RuntimeStore` 是继续单 facade，还是拆成 `SessionStore` / `OperationStore` / `WorkflowRunStore` / `EventStore`。

验收：

- [x] 新增核心 API validation 测试。
- [x] 没有 adapter 需要依赖 runtime internals。
- [x] public exception 都有稳定 code 和 safe details。

### M1：Runtime lifecycle、Inspection 与 Event Taxonomy

目标：在 HTTP/CLI 前，让 operation/workflow run 具有稳定生命周期和可检查性。

主要文件：

- `helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java`
- `helm-runtime/src/main/java/io/agent/helm/runtime/WorkflowRuntime.java`
- `helm-core/src/main/java/io/agent/helm/core/store/OperationRecord.java`
- `helm-core/src/main/java/io/agent/helm/core/store/WorkflowRunRecord.java`
- `helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventRecord.java`

交付：

- [x] `OperationStatus`、`WorkflowRunStatus` enum 替换裸字符串状态。
- [x] `AgentRuntime.getOperation(operationId)`。
- [x] `AgentRuntime.getOperationEvents(operationId)`。
- [x] `WorkflowRuntime.getRun(runId)`。
- [x] `WorkflowRuntime.listRuns(...)`。
- [x] `WorkflowRuntime.getRunEvents(runId)`。
- [x] `OperationHandle`，为未来 async 保留稳定形态。
- [x] `AgentRuntime.dispatch(...)` admission API，MVP 内部可同步执行。
- [x] 定义 event type taxonomy：operation、workflow、turn、model、tool、skill、sandbox、error。
- [x] 所有事件入 store 前脱敏。

验收：

- [x] 状态转换测试覆盖 success/failure。
- [x] terminal record 在返回或抛错前已持久化。
- [x] inspection API 不暴露 developer-only details。
- [x] event ordering 稳定。

### M2：Contract-first Persistence 与 JDBC

目标：先定义可测试的持久化合约，再实现 JDBC。

未来模块：

- `helm-runtime-testkit`
- `helm-persistence-jdbc`

交付：

- [x] Store 接口方法级 invariant 文档。
- [x] session version / optimistic locking 合约。
- [x] operation/workflow run listing 合约。
- [x] event sequence / ordering 合约。
- [x] JUnit contract tests：in-memory 与 JDBC 共用。
- [x] `JdbcRuntimeStore`。
- [x] Flyway migrations。
- [ ] H2 测试；PostgreSQL baseline。
- [ ] schema version 检查：未知或更新版本 fail-fast。

验收：

- [x] in-memory 和 JDBC 均通过 testkit。
- [x] 并发 session update 不会静默覆盖。
- [x] migrations 幂等。
- [x] 结构化错误可安全持久化。

### M3：Engine hardening、Tool Validation 与 Context

目标：在真实 provider 前强化 agent engine。

主要文件：

- `helm-agent-engine/src/main/java/io/agent/helm/engine/AgentEngine.java`
- `helm-agent-engine/src/main/java/io/agent/helm/engine/TurnRunner.java`
- `helm-core/src/main/java/io/agent/helm/core/tool/Tool.java`
- `helm-core/src/main/java/io/agent/helm/core/type/JsonSchema.java`

交付：

- [x] Engine event callback 或 observer hook。
- [x] model turn start/end/fail events。
- [x] tool start/end/fail events。
- [x] tool input/output validation。
- [x] token usage aggregation。
- [x] max-turn、timeout、provider、tool error 全部结构化。
- [x] 初始 context overflow 分类。
- [x] `JsonSchema` 扩展 Map、enum、nested record、optional/nullability。

验收：

- [x] engine control flow 不泄漏裸 `IllegalStateException`。
- [x] invalid tool input 在用户 tool code 前失败。
- [x] invalid tool output 映射为 `ToolExecutionException`。
- [x] usage 出现在 result 和 event 中。

### M4：真实 Provider

目标：接入真实 LLM，同时隔离 provider SDK。

未来模块：

- `helm-provider-openai`
- `helm-provider-anthropic`

交付：

- [x] provider config：api key source、base URL、model allowlist、timeout、retry。
- [x] OpenAI streaming adapter。
- [x] Anthropic streaming adapter。
- [x] Helm `JsonSchema` 到 provider tool schema 映射。
- [x] provider error taxonomy。
- [x] mock-server contract tests。
- [ ] optional live smoke tests，默认 CI 不跑。

验收：

- [x] 无真实网络/credential 单元测试。
- [x] credential 不进入 events/logs/safe errors。
- [x] terminal text、tool call、provider error、timeout、usage 均有测试。

### M5：Skills 与 Sandbox

目标：实现文档中已定义的安全边界。

未来模块：

- `helm-sandbox-local`

交付：

- [x] `SkillDefinition`。
- [x] classpath skill loader。
- [ ] directory skill loader。
- [x] skill root enforcement。
- [x] `InMemorySandbox`。
- [x] `LocalSandbox`。
- [x] 路径 normalize、拒绝 traversal/absolute escape/symlink escape。
- [x] shell 默认关闭。
- [x] timeout、output limit、env allowlist、command policy。

验收：

- [x] path traversal / symlink escape 测试通过。
- [x] shell 默认禁用测试通过。
- [x] allowed env 显式配置。
- [x] 文档明确 LocalSandbox 不是生产隔离。

### M6：HTTP Core、Servlet Adapter 与 Client SDK

目标：安全暴露 Helm runtime，而不让 core 依赖 web framework。

未来模块：

- `helm-http-core`
- `helm-http-servlet`
- `helm-client`

交付：

- [x] framework-neutral HTTP DTOs。
- [x] handler abstraction。
- [x] Servlet adapter。
- [x] prompt、dispatch、workflow invoke、operation inspection、run inspection routes。
- [x] event read API：先 paged events，后续可升级 stream/SSE。
- [x] `HelmSecurityContext`。
- [x] `HelmAuthorizer`。
- [x] request body size/depth/timeouts。
- [x] Java client SDK。

验收：

- [x] HTTP opt-in。
- [x] 每个 route 执行前可授权。
- [x] error response 只含 code + safe details。
- [x] `helm-http-core` 无 Servlet/Spring 依赖。

### M7：CLI 与开发体验

目标：让本地运行、调试、检查变得简单。

未来模块：

- `helm-cli`

交付：

- [x] Picocli `helm` binary。
- [x] `helm dev`。
- [x] `helm run`。
- [ ] `helm agents`。
- [ ] `helm workflows`。
- [x] `helm inspect operation`。
- [x] `helm inspect workflow-run`。
- [x] JSON/human output。
- [ ] local dev config / `.env`。
- [x] 默认 bind `127.0.0.1`。

验收：

- [x] FakeProvider workflow 可通过 CLI 运行。
- [x] CLI 可查看 operation/run/events。
- [x] CLI 不成为 core/runtime 依赖。

### M8：Spring Boot Starter

目标：提供 Java 生产服务最常用集成方式。

未来模块：

- `helm-spring-boot-starter`

交付：

- [x] auto-config `AgentRuntime` / `WorkflowRuntime`。
- [x] bean discovery：agents、workflows、tools、providers、store、observers。
- [x] `application.yml` properties。
- [x] conditional HTTP route registration。
- [ ] duplicate names fail-fast。
- [ ] Spring Boot 示例。

验收：

- [x] core/runtime/engine 保持 Spring-free。
- [x] HTTP 默认关闭。
- [x] app context tests 覆盖常见配置。

### M9：Observability、Redaction 与 Operations

目标：生产可调试，同时默认不泄漏敏感内容。

未来模块：

- `helm-observability-logging`
- `helm-observability-opentelemetry`（后续）

交付：

- [x] Observer SPI。
- [x] logging observer。
- [x] content capture policy。
- [x] redaction annotation/descriptor。
- [x] metrics：duration、failure code、provider/tool latency、token usage。
- [x] trace correlation fields。

验收：

- [x] 默认日志只记录 metadata/summary。
- [x] developer details 默认不输出。
- [x] operation/run/model/tool 可通过 ID 关联。

### M10：Docs、Blueprints 与 Release Engineering

目标：让 Helm 可被外部 Java 开发者采用。

交付：

- [x] `CHANGELOG.md`。
- [x] `CONTRIBUTING.md`。
- [x] license 决策。
- [x] Maven wrapper。
- [x] CI：compile、tests、Spotless、dependency checks、Javadocs。
- [x] Maven publishing checklist。
- [ ] clean consumer sample。
- [ ] package-specific READMEs。
- [x] adapter implementation guides：provider、persistence、sandbox、observability、Spring。
- [x] runnable examples：FakeProvider、real provider、JDBC、HTTP、Spring Boot、sandbox、typed tools。

验收：

- [x] clean checkout build 通过。
- [ ] 外部 sample project 可消费本地/发布 Maven artifacts。
- [x] docs 与实现 API 一致。
- [x] examples 可执行，不只是目标 API 文档。

### M11：Post-GA Durable Scale Runtime

目标：在基础生产能力稳定后再做高级 durable execution。

交付：

- [x] async workers / queue-backed admission。
- [x] per-session FIFO queue。
- [x] claim/lease/renew/recovery。
- [ ] turn journal。
- [ ] stream chunk recovery。
- [ ] cancellation。
- [ ] provider routing/fallback。
- [ ] remote/container sandbox adapters。

验收：

- [ ] accepted work 可恢复且语义有文档。
- [ ] 不盲目重放不确定副作用。
- [ ] side-effecting tool retry 需要 idempotency policy。

## 6. 当前推荐的下一步开发切片

M0–M10 已完成，下一步聚焦剩余缺口，按优先级：

1. **Durable scale 收尾（M11 续）**：
   - `TurnJournal` 从 SPI 占位升级为可恢复实现（per-session FIFO + chunk 落盘）。
   - stream-chunk recovery：流中断后可基于 journal 续传，不重放不确定副作用。
   - durable cancellation：跨进程取消信号 + lease 释放。
   - provider routing/fallback：多 provider 故障切换。
2. **Remote / container sandbox**：定义 `RemoteSandbox` SPI 与默认容器化适配（OCI/docker exec 或独立 sandbox 服务），生产隔离不依赖 local sandbox。
3. **真实 vector store 适配**：将 `SemanticMemoryStore` 的 `EmbeddingStore` 接到真实向量库（pgvector / Chroma / Qdrant），替换 `InMemoryEmbeddingStore`/`FakeEmbeddingProvider`。
4. **0.1.0 发布前准备**：pre-1.0 API 兼容性策略（允许破坏性变更但需文档化）、`helm-bom` 对齐、Javadoc 发布、Maven Central 发布 checklist 实跑、外部 sample project 验证可消费 artifacts。

开发方式：

- TDD：先写 JUnit/AssertJ 测试。
- 每个切片后运行：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

- 修改后使用 code-reviewer agent，至少处理 CRITICAL/HIGH。

## 7. 进度跟踪

### 当前活跃 Milestone

- Active: M11 Post-GA durable scale（部分：`WorkQueue` + claim/lease/recovery 已完成；`TurnJournal` 仅 SPI、stream-chunk 恢复 / durable cancellation / provider routing / remote sandbox 待做）
- M0–M10：complete
- Recommended next: M11 收尾 + remote/container sandbox + 真实 vector adapter + 0.1.0 发布准备（见 §6）

### 当前阻塞项

| Blocker | Owner | Resolution |
| --- | --- | --- |
| ~~Maven groupId / 发布命名空间未最终确定~~ | project | 已解决：`io.agent.helm` |
| ~~License 未确定~~ | project | 已解决：Apache 2.0 |
| ~~`RuntimeStore` 是否拆分子接口未定~~ | project | 已解决：拆为 `SessionStore`/`OperationStore`/`WorkflowRunStore`/`EventStore` |
| durable `TurnJournal` / stream-chunk 恢复 / durable cancellation 未实现 | project | M11 收尾 |
| remote / container sandbox 未实现 | project | 新 SPI + 容器化适配 |
| 真实 vector store adapter 未对接 | project | 接 pgvector/Chroma/Qdrant 之一 |
| 0.1.0 发布：Maven Central 实跑 + 外部 sample 消费验证 | project | 发布前 checklist |

### 近期任务 Backlog

| ID | 状态 | 任务 | Milestone | 验收 |
| --- | --- | --- | --- | --- |
| H-001 | complete | 增加 `AgentConfig` validation 测试与实现 | M0 | 缺 model、重复 tool name 等 fail-fast |
| H-002 | complete | 引入 operation/workflow status enum | M1 | 不再在核心 record 中使用裸字符串状态 |
| H-003 | complete | 增加 runtime inspection APIs | M1 | 可查询 operation/run/events |
| H-004 | complete | 定义基础 event taxonomy | M1 | operation/workflow/model/tool events 有稳定类型 |
| H-005 | complete | 增加 `OperationHandle` 与 `dispatch` API | M1 | dispatch 返回 admission receipt/handle，不混同 workflow run |
| H-006 | complete | 起草 `docs/contracts/runtime-store.md` | M2 | 写明版本、并发、event ordering、listing invariant |
| H-007 | active | durable `TurnJournal` 实现 + stream-chunk 恢复 | M11 | 流中断后可基于 journal 续传，不重放不确定副作用 |
| H-008 | active | durable cancellation + provider routing/fallback | M11 | 跨进程取消信号 + lease 释放；多 provider 故障切换 |
| H-009 | proposed | remote / container sandbox adapter | M11 | `RemoteSandbox` SPI + 容器化默认适配 |
| H-010 | proposed | 真实 vector store adapter（pgvector/Chroma/Qdrant） | memory 语义 | `SemanticMemoryStore` 接到真实向量库，替换 InMemory/Fake |
| H-011 | proposed | 0.1.0 发布准备：Maven Central 实跑 + 外部 sample 消费 | M10 | 发布 checklist 全绿，外部 project 可消费 artifacts |

### 进度日志

| Date | Update |
| --- | --- |
| 2026-07-05 | 全仓库代码审查与修复（9 commits）：core SPI 加固（HelmException cause 链、SessionStore OCC、Store SPI 分页、WorkQueue.complete 终态、JsonSchema 覆盖 UUID/Instant/Set、ToolContext 扩展、ModelStreamEvent.Completed finishReason、TokenUsage cache/reasoning、HelmAction +TOOL_EXECUTE/MEMORY_WRITE/SANDBOX_COMMAND、Sandbox.disabled() 默认）；engine/runtime durable 正确性（session 锁、按 operationId claim、幂等恢复、流超时作失败）+ 流式失败事件 + AgentRuntime AutoCloseable；provider system prompt / SSE 错误 / schema 保真 / 取消传播；sandbox env 隔离默认 / 进程树 kill / TOCTOU 缓解；HTTP body 上限 / routePrefix / authz 信封 / 可伪造 header 默认关闭 / SSE 错误上报 / 优雅 shutdown；client AutoCloseable / 去重头 / 增量流式 / 错误码保留；persistence OCC / 幂等事件 / span TTL / LIKE 转义 / cause 链；memory 解耦 IndexedEmbeddingStore / 维度校验；testkit smoke test。`mvn verify` 全绿（809 测试，22 模块）。 |
| 2026-07-05 | 实现 11 个生产化组件设计：API governance（`ErrorCode` 注册表/`@Preview`/`@Experimental`/`RuntimeStore` 拆 `SessionStore`/`OperationStore`/`WorkflowRunStore`/`EventStore` 子接口/groupId 迁移 `io.agent.helm`/`helm-bom`/`docs/contracts/error-codes.md`）、JsonSchema 扩展（Map/enum/Optional/`@SchemaDescription`）、engine hardening（`EngineEvent`/`EngineEventListener` 桥接 `RuntimeEventRecord`、tool input/output 校验、token usage 聚合、`ContextOverflow` 三类、`EngineException` 层级替代裸 `IllegalStateException`）、streaming（`PromptStreamEvent` + `promptStream` `@Preview` + SSE route）、authorizer（`HelmSecurityContext`/`HelmAuthorizer`/`HelmAction`/`HelmResource` + AgentRuntime admission）、rate limiting（`RateLimiter`/`RateLimitKey` + admission）、memory 语义检索（`helm-memory-semantic`：`SemanticMemoryStore`/`EmbeddingProvider`/`EmbeddingStore`/`InMemoryEmbeddingStore`/`FakeEmbeddingProvider`）、HTTP client SDK（`helm-client`：`HelmClient`/`JdkHttpTransport`/`ClientErrorMapper`/`SseParser` + WireMock 测试）、metrics/OTel（`helm-observability-opentelemetry`：`OpenTelemetryRuntimeObserver` metrics+tracing/`@Redact`/`ContentCaptureLevel`）、release engineering（LICENSE Apache 2.0/`CHANGELOG`/`CONTRIBUTING`/`ci.yml`/`mvnw`/5 篇 adapter guides）、durable SPI 占位（`WorkQueue`/`TurnJournal` `@Preview` post-GA）。新增 3 模块 + `helm-bom`。`mvn verify` 全绿（20 模块）。 |
| 2026-07-05 | 完成生产组件缺口分析并补齐 Memory/Session 管理：`helm-core` 新增 `MemoryRecord`/`MemoryStore` SPI 与 `MemoryStoreContractTest`，`RuntimeStore` 增加 `listSessions`/`deleteSession`；`helm-runtime` 新增 `InMemoryMemoryStore`、内置 `save_memory` tool、记忆注入 instructions、`AgentRuntime` session 管理 API（list/get/reset）与 `maxSessionMessages` 历史裁剪；`helm-persistence-jdbc` 新增 `JdbcMemoryStore` 与 `V2__memory.sql`；新增 `examples/memory-session-example` 端到端验证。详见第 3.1 节。 |
| 2026-07-04 | 完成系统设计 Milestone 5：新增 `helm-persistence-jdbc`（`JdbcRuntimeStore` 注入 DataSource，JSON 列存负载，Flyway `V1__init.sql` 迁移，SQL 异常映射 `PersistenceException`）与 `helm-observability-logging`（`LoggingRuntimeObserver` 结构化 SLF4J 日志，仅记元数据不记 payload）；`helm-core` 新增 `RuntimeEventObserver` SPI。`RuntimeStoreContractTest` 在 InMemory 与 JDBC（H2）上均通过；文件模式 H2 重启恢复测试通过。`mvn verify` 全绿（170 测试）。系统设计 M1–M5 全部完成。 |
| 2026-07-04 | 完成系统设计 Milestone 3：新增 `helm-http-core`、`helm-http-servlet`、`helm-cli`；框架无关 HTTP DTO/路由/错误映射，`HelmHttpServlet` 适配 Jakarta Servlet，Picocli `helm run`/`dev`/`operations`/`runs`/`run-detail`。`HttpErrorContractTest` 在 router 与 servlet（Jetty）上均通过；CLI 端到端测试通过；`mvn verify` 全绿（132 测试），`helm-http-core` 不依赖 Servlet。 |
| 2026-07-04 | 完成系统设计 Milestone 2：新增 `helm-provider-openai`、`helm-provider-anthropic`、`helm-sandbox-local`；`helm-core` 发布 test-jar 提供 `ModelProviderContractTest`/`SandboxContractTest` 契约基类；`helm-runtime` 增加 `ClasspathSkillLoader`。FakeProvider/InMemorySandbox/LocalSandbox 及两个真实 provider 均通过契约测试；切换验证通过；`mvn verify` 全绿（107 测试），core 三模块无新增生产依赖。 |
| 2026-06-29 | 完成 M0/M1 first slice：`AgentConfig` validation、operation/workflow status enum、runtime inspection API、event taxonomy、`OperationHandle`/`dispatch`、`docs/contracts/runtime-store.md`。 |
| 2026-06-28 | 创建生产路线图，形成 M0-M11 计划，并合并会话级 findings/progress 到单一长期文档。 |

### 验证记录

| Date | Command | Result |
| --- | --- | --- |
| 2026-07-05 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；809 tests；Spotless passed（审查修复后） |
| 2026-07-04 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；170 tests；Spotless passed（M5 后） |
| 2026-07-04 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；141 tests；Spotless passed（M4 后） |
| 2026-07-04 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；132 tests；Spotless passed（M3 后） |
| 2026-07-04 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；107 tests；Spotless passed（M2） |
| 2026-06-29 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；47 tests；Spotless passed |
| 2026-06-28 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify` | BUILD SUCCESS；35 tests；Spotless passed |

### 偏差与修正记录

| Date | Decision / Correction | Reason |
| --- | --- | --- |
| 2026-06-28 | 将高级 durable execution 作为 Post-GA 方向，而不是 MVP 前置要求 | 避免在同步 runtime 状态未稳定前引入 lease/journal/recovery 复杂度 |
| 2026-06-28 | 将 event taxonomy 提前到 HTTP/CLI/Spring 之前 | 避免多个外层 adapter 各自定义不兼容事件模型 |
| 2026-06-28 | 暂缓 channel 生态 | 生产核心能力优先，完整 channel 生态不属于 MVP |
| 2026-06-28 | 合并 planning 文件，只保留本路线图作为长期跟踪入口 | 减少文件数量，降低维护成本 |

## 8. 参考资料

- `README.md`
- `AGENTS.md`
- `docs/helm-mvp-design.md`
- `docs/examples/milestone-1-agent-workflow.md`
- `examples/coding-workflow/README.md`
