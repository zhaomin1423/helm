# Helm Production Roadmap

本文档是 Helm 后续开发的唯一长期计划与进度跟踪入口。它聚焦 Helm 自身目标：把当前 Java 21 Agent Harness MVP 基座推进到可用于生产环境的框架能力。

## 1. 当前状态

Helm 当前是 Java 21 Maven 多模块项目，已经具备 Milestone 1 基础：

- `helm-core`：公开 API/SPI、消息模型、结构化错误、tool、workflow、sandbox、store 合约。
- `helm-agent-engine`：基础 agent loop、stream 聚合、tool-call 执行。
- `helm-runtime`：agent/workflow orchestration、`FakeProvider`、`InMemoryRuntimeStore`、基础事件记录与脱敏。
- `examples/coding-workflow`：基于 FakeProvider 的软件开发自动化 workflow 示例。

最近验证命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -f /Users/zhaomin/Files/projects/helm/pom.xml verify
```

验证结果：BUILD SUCCESS，35 个测试通过，Spotless 通过。

当前结论：Helm 是一个清晰的 MVP 基座，但还不是生产可用版本。

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

## 4. Milestone 总览

状态取值：`proposed`、`active`、`blocked`、`complete`。

| Milestone | 状态 | 优先级 | 目标 | 依赖 |
| --- | --- | --- | --- | --- |
| M0 API 边界与模块策略 | proposed | P0 | 冻结核心 public/internal 边界、验证 builder/API 合约 | 当前 MVP 基座 |
| M1 Runtime lifecycle / inspection / events | proposed | P0 | operation/run 状态、inspection API、event taxonomy、dispatch admission | M0 |
| M2 Contract-first persistence / JDBC | proposed | P0 | Store SPI、testkit、JDBC、migration、version conflict | M1 |
| M3 Engine hardening / tool validation / context | proposed | P0 | engine events、tool validation、usage、structured engine errors | M1 |
| M4 Real providers | proposed | P0 | OpenAI/Anthropic provider、mock contract tests、credential redaction | M1/M3 |
| M5 Skills and sandbox | proposed | P0/P1 | skill loader、InMemorySandbox、LocalSandbox、安全策略 | M0/M1 |
| M6 HTTP core / servlet / client | proposed | P0/P1 | HTTP DTO/handler、Servlet adapter、authorizer、client SDK | M1/M2 |
| M7 CLI and developer experience | proposed | P1 | dev/run/inspect/list commands、JSON/human output | M6 |
| M8 Spring Boot starter | proposed | P1 | auto-config、bean discovery、properties、示例 | M6/M2 |
| M9 Observability and operations | proposed | P1 | observer SPI、logging、metrics、OpenTelemetry 预备 | M1/M3 |
| M10 Docs / blueprints / release engineering | proposed | P0/P1 | CI、license、changelog、Maven publish、blueprints、examples | 横向贯穿 |
| M11 Post-GA durable scale | proposed | P2 | async workers、queue、lease/recovery、remote sandbox、routing/fallback | M1-M10 |

## 5. Milestone 详细计划

### M0：API 边界与模块策略

目标：在新增 adapter 前稳定核心边界，避免后续模块倒逼 public API 失控。

主要文件：

- `pom.xml`
- `helm-core/src/main/java/io/agent/helm/core/agent/AgentConfig.java`
- `helm-core/src/main/java/io/agent/helm/core/store/RuntimeStore.java`
- `docs/helm-mvp-design.md`

交付：

- [ ] 确认 Maven groupId/artifact 命名策略。
- [ ] 写明 public package、SPI package、internal package 规则。
- [x] `AgentConfig` builder 增加必填校验：model、instructions 默认、duplicate tool name、unsafe sandbox 默认。
- [ ] 明确 pre-1.0 API compatibility policy。
- [x] 决定 `RuntimeStore` 是继续单 facade，还是拆成 `SessionStore` / `OperationStore` / `WorkflowRunStore` / `EventStore`。

验收：

- [x] 新增核心 API validation 测试。
- [x] 没有 adapter 需要依赖 runtime internals。
- [ ] public exception 都有稳定 code 和 safe details。

### M1：Runtime lifecycle、Inspection 与 Event Taxonomy

目标：在 HTTP/CLI 前，让 operation/workflow run 具有稳定生命周期和可检查性。

主要文件：

- `helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java`
- `helm-runtime/src/main/java/io/agent/helm/runtime/WorkflowRuntime.java`
- `helm-core/src/main/java/io/agent/helm/core/store/OperationRecord.java`
- `helm-core/src/main/java/io/agent/helm/core/store/WorkflowRunRecord.java`
- `helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventRecord.java`

交付：

- [ ] `OperationStatus`、`WorkflowRunStatus` enum 替换裸字符串状态。
- [ ] `AgentRuntime.getOperation(operationId)`。
- [ ] `AgentRuntime.getOperationEvents(operationId)`。
- [ ] `WorkflowRuntime.getRun(runId)`。
- [ ] `WorkflowRuntime.listRuns(...)`。
- [ ] `WorkflowRuntime.getRunEvents(runId)`。
- [ ] `OperationHandle`，为未来 async 保留稳定形态。
- [ ] `AgentRuntime.dispatch(...)` admission API，MVP 内部可同步执行。
- [ ] 定义 event type taxonomy：operation、workflow、turn、model、tool、skill、sandbox、error。
- [ ] 所有事件入 store 前脱敏。

验收：

- [ ] 状态转换测试覆盖 success/failure。
- [ ] terminal record 在返回或抛错前已持久化。
- [ ] inspection API 不暴露 developer-only details。
- [ ] event ordering 稳定。

### M2：Contract-first Persistence 与 JDBC

目标：先定义可测试的持久化合约，再实现 JDBC。

未来模块：

- `helm-runtime-testkit`
- `helm-persistence-jdbc`

交付：

- [ ] Store 接口方法级 invariant 文档。
- [ ] session version / optimistic locking 合约。
- [ ] operation/workflow run listing 合约。
- [ ] event sequence / ordering 合约。
- [ ] JUnit contract tests：in-memory 与 JDBC 共用。
- [ ] `JdbcRuntimeStore`。
- [ ] Flyway migrations。
- [ ] H2 测试；PostgreSQL baseline。
- [ ] schema version 检查：未知或更新版本 fail-fast。

验收：

- [ ] in-memory 和 JDBC 均通过 testkit。
- [ ] 并发 session update 不会静默覆盖。
- [ ] migrations 幂等。
- [ ] 结构化错误可安全持久化。

### M3：Engine hardening、Tool Validation 与 Context

目标：在真实 provider 前强化 agent engine。

主要文件：

- `helm-agent-engine/src/main/java/io/agent/helm/engine/AgentEngine.java`
- `helm-agent-engine/src/main/java/io/agent/helm/engine/TurnRunner.java`
- `helm-core/src/main/java/io/agent/helm/core/tool/Tool.java`
- `helm-core/src/main/java/io/agent/helm/core/type/JsonSchema.java`

交付：

- [ ] Engine event callback 或 observer hook。
- [ ] model turn start/end/fail events。
- [ ] tool start/end/fail events。
- [ ] tool input/output validation。
- [ ] token usage aggregation。
- [ ] max-turn、timeout、provider、tool error 全部结构化。
- [ ] 初始 context overflow 分类。
- [ ] `JsonSchema` 扩展 Map、enum、nested record、optional/nullability。

验收：

- [ ] engine control flow 不泄漏裸 `IllegalStateException`。
- [ ] invalid tool input 在用户 tool code 前失败。
- [ ] invalid tool output 映射为 `ToolExecutionException`。
- [ ] usage 出现在 result 和 event 中。

### M4：真实 Provider

目标：接入真实 LLM，同时隔离 provider SDK。

未来模块：

- `helm-provider-openai`
- `helm-provider-anthropic`

交付：

- [ ] provider config：api key source、base URL、model allowlist、timeout、retry。
- [ ] OpenAI streaming adapter。
- [ ] Anthropic streaming adapter。
- [ ] Helm `JsonSchema` 到 provider tool schema 映射。
- [ ] provider error taxonomy。
- [ ] mock-server contract tests。
- [ ] optional live smoke tests，默认 CI 不跑。

验收：

- [ ] 无真实网络/credential 单元测试。
- [ ] credential 不进入 events/logs/safe errors。
- [ ] terminal text、tool call、provider error、timeout、usage 均有测试。

### M5：Skills 与 Sandbox

目标：实现文档中已定义的安全边界。

未来模块：

- `helm-sandbox-local`

交付：

- [ ] `SkillDefinition`。
- [ ] classpath skill loader。
- [ ] directory skill loader。
- [ ] skill root enforcement。
- [ ] `InMemorySandbox`。
- [ ] `LocalSandbox`。
- [ ] 路径 normalize、拒绝 traversal/absolute escape/symlink escape。
- [ ] shell 默认关闭。
- [ ] timeout、output limit、env allowlist、command policy。

验收：

- [ ] path traversal / symlink escape 测试通过。
- [ ] shell 默认禁用测试通过。
- [ ] allowed env 显式配置。
- [ ] 文档明确 LocalSandbox 不是生产隔离。

### M6：HTTP Core、Servlet Adapter 与 Client SDK

目标：安全暴露 Helm runtime，而不让 core 依赖 web framework。

未来模块：

- `helm-http-core`
- `helm-http-servlet`
- `helm-client`

交付：

- [ ] framework-neutral HTTP DTOs。
- [ ] handler abstraction。
- [ ] Servlet adapter。
- [ ] prompt、dispatch、workflow invoke、operation inspection、run inspection routes。
- [ ] event read API：先 paged events，后续可升级 stream/SSE。
- [ ] `HelmSecurityContext`。
- [ ] `HelmAuthorizer`。
- [ ] request body size/depth/timeouts。
- [ ] Java client SDK。

验收：

- [ ] HTTP opt-in。
- [ ] 每个 route 执行前可授权。
- [ ] error response 只含 code + safe details。
- [ ] `helm-http-core` 无 Servlet/Spring 依赖。

### M7：CLI 与开发体验

目标：让本地运行、调试、检查变得简单。

未来模块：

- `helm-cli`

交付：

- [ ] Picocli `helm` binary。
- [ ] `helm dev`。
- [ ] `helm run`。
- [ ] `helm agents`。
- [ ] `helm workflows`。
- [ ] `helm inspect operation`。
- [ ] `helm inspect workflow-run`。
- [ ] JSON/human output。
- [ ] local dev config / `.env`。
- [ ] 默认 bind `127.0.0.1`。

验收：

- [ ] FakeProvider workflow 可通过 CLI 运行。
- [ ] CLI 可查看 operation/run/events。
- [ ] CLI 不成为 core/runtime 依赖。

### M8：Spring Boot Starter

目标：提供 Java 生产服务最常用集成方式。

未来模块：

- `helm-spring-boot-starter`

交付：

- [ ] auto-config `AgentRuntime` / `WorkflowRuntime`。
- [ ] bean discovery：agents、workflows、tools、providers、store、observers。
- [ ] `application.yml` properties。
- [ ] conditional HTTP route registration。
- [ ] duplicate names fail-fast。
- [ ] Spring Boot 示例。

验收：

- [ ] core/runtime/engine 保持 Spring-free。
- [ ] HTTP 默认关闭。
- [ ] app context tests 覆盖常见配置。

### M9：Observability、Redaction 与 Operations

目标：生产可调试，同时默认不泄漏敏感内容。

未来模块：

- `helm-observability-logging`
- `helm-observability-opentelemetry`（后续）

交付：

- [ ] Observer SPI。
- [ ] logging observer。
- [ ] content capture policy。
- [ ] redaction annotation/descriptor。
- [ ] metrics：duration、failure code、provider/tool latency、token usage。
- [ ] trace correlation fields。

验收：

- [ ] 默认日志只记录 metadata/summary。
- [ ] developer details 默认不输出。
- [ ] operation/run/model/tool 可通过 ID 关联。

### M10：Docs、Blueprints 与 Release Engineering

目标：让 Helm 可被外部 Java 开发者采用。

交付：

- [ ] `CHANGELOG.md`。
- [ ] `CONTRIBUTING.md`。
- [ ] license 决策。
- [ ] Maven wrapper。
- [ ] CI：compile、tests、Spotless、dependency checks、Javadocs。
- [ ] Maven publishing checklist。
- [ ] clean consumer sample。
- [ ] package-specific READMEs。
- [ ] adapter implementation guides：provider、persistence、sandbox、observability、Spring。
- [ ] runnable examples：FakeProvider、real provider、JDBC、HTTP、Spring Boot、sandbox、typed tools。

验收：

- [ ] clean checkout build 通过。
- [ ] 外部 sample project 可消费本地/发布 Maven artifacts。
- [ ] docs 与实现 API 一致。
- [ ] examples 可执行，不只是目标 API 文档。

### M11：Post-GA Durable Scale Runtime

目标：在基础生产能力稳定后再做高级 durable execution。

交付：

- [ ] async workers / queue-backed admission。
- [ ] per-session FIFO queue。
- [ ] claim/lease/renew/recovery。
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

下一步不要直接做 provider 或 JDBC，建议先做 M0/M1 的小闭环：

1. `AgentConfig` validation：model 必填、duplicate tool name、null policy。
2. `OperationStatus` / `WorkflowRunStatus` enum。
3. `AgentRuntime` / `WorkflowRuntime` inspection API。
4. event taxonomy 基础类型。
5. `OperationHandle` 与 `dispatch` admission API。
6. 更新 `RuntimeStore` 合约文档或拆分 store 子接口。

开发方式：

- TDD：先写 JUnit/AssertJ 测试。
- 每个切片后运行：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

- 修改后使用 code-review agent，至少处理 CRITICAL/HIGH。

## 7. 进度跟踪

### 当前活跃 Milestone

- Active: _none_（系统设计 Milestone 3 HTTP/CLI 已完成）
- Recommended next: 系统设计 Milestone 4 Spring Boot Starter（见 `docs/helm-system-design-milestones.md`）

### 当前阻塞项

| Blocker | Owner | Resolution |
| --- | --- | --- |
| Maven groupId / 发布命名空间未最终确定 | project | 在 M0 中决策 |
| License 未确定 | project | M10 前必须决策 |
| `RuntimeStore` 是否拆分子接口未定 | project | M0/M2 设计决策 |

### 近期任务 Backlog

| ID | 状态 | 任务 | Milestone | 验收 |
| --- | --- | --- | --- | --- |
| H-001 | complete | 增加 `AgentConfig` validation 测试与实现 | M0 | 缺 model、重复 tool name 等 fail-fast |
| H-002 | complete | 引入 operation/workflow status enum | M1 | 不再在核心 record 中使用裸字符串状态 |
| H-003 | complete | 增加 runtime inspection APIs | M1 | 可查询 operation/run/events |
| H-004 | complete | 定义基础 event taxonomy | M1 | operation/workflow/model/tool events 有稳定类型 |
| H-005 | complete | 增加 `OperationHandle` 与 `dispatch` API | M1 | dispatch 返回 admission receipt/handle，不混同 workflow run |
| H-006 | complete | 起草 `docs/contracts/runtime-store.md` | M2 | 写明版本、并发、event ordering、listing invariant |

### 进度日志

| Date | Update |
| --- | --- |
| 2026-07-04 | 完成系统设计 Milestone 3：新增 `helm-http-core`、`helm-http-servlet`、`helm-cli`；框架无关 HTTP DTO/路由/错误映射，`HelmHttpServlet` 适配 Jakarta Servlet，Picocli `helm run`/`dev`/`operations`/`runs`/`run-detail`。`HttpErrorContractTest` 在 router 与 servlet（Jetty）上均通过；CLI 端到端测试通过；`mvn verify` 全绿（132 测试），`helm-http-core` 不依赖 Servlet。 |
| 2026-07-04 | 完成系统设计 Milestone 2：新增 `helm-provider-openai`、`helm-provider-anthropic`、`helm-sandbox-local`；`helm-core` 发布 test-jar 提供 `ModelProviderContractTest`/`SandboxContractTest` 契约基类；`helm-runtime` 增加 `ClasspathSkillLoader`。FakeProvider/InMemorySandbox/LocalSandbox 及两个真实 provider 均通过契约测试；切换验证通过；`mvn verify` 全绿（107 测试），core 三模块无新增生产依赖。 |
| 2026-06-29 | 完成 M0/M1 first slice：`AgentConfig` validation、operation/workflow status enum、runtime inspection API、event taxonomy、`OperationHandle`/`dispatch`、`docs/contracts/runtime-store.md`。 |
| 2026-06-28 | 创建生产路线图，形成 M0-M11 计划，并合并会话级 findings/progress 到单一长期文档。 |

### 验证记录

| Date | Command | Result |
| --- | --- | --- |
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
