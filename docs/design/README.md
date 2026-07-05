# Helm 组件设计文档

本目录是 Helm 后续生产化能力的**组件级设计方案集合**。它不是实现计划，而是为 `docs/roadmap.md` 中尚未实现的生产能力逐个给出可评审的设计方案：每个组件一份文档，独立设计、互相对齐。

- 长期计划与进度跟踪：[`docs/roadmap.md`](../roadmap.md)
- MVP 设计（"是什么"）：[`docs/helm-mvp-design.md`](../helm-mvp-design.md)
- 里程碑拆分：[`docs/helm-system-design-milestones.md`](../helm-system-design-milestones.md)
- Store 合约：[`docs/contracts/runtime-store.md`](../contracts/runtime-store.md)

## 1. 目的

Helm 已完成系统设计 Milestone 1–5（core/engine/runtime、provider、sandbox、HTTP、CLI、Spring Boot starter、JDBC 持久化、logging observer），并在 2026-07-05 补齐了 Memory / Session 管理 / 历史裁剪。这些是**已落地**的基座。

要推进到生产可用，仍有一批横向能力缺失。这些能力不能在一篇文档里塞下，否则设计会失焦、互相耦合。本目录把它们按组件拆开，每篇文档回答一个问题：

> 这个组件**现在缺什么、应该长成什么样、放在哪个模块、暴露什么 SPI/API、如何测试、如何验收**？

设计完成后，每一篇都可以独立进入 `docs/superpowers/plans/` 的实施计划阶段。

## 2. 缺口分析（2026-07-05）

对照 `docs/roadmap.md` 第 3.1 节"仍留待后续 milestone 的生产能力"与第 4 节 milestone 表中 `proposed` 的横向项，确认以下 11 个缺失组件。每条都给出：来源 milestone、现状、设计文档。

| # | 组件 | 来源 | 现状 | 设计文档 |
| --- | --- | --- | --- | --- |
| 1 | Streaming API 暴露 | M3 | `ModelProvider.stream` 已用 `Flow.Publisher`，但 `AgentSession.prompt` / `AgentEngine.run` 同步聚合，不向调用方暴露增量 token | [`01-streaming-api.md`](01-streaming-api.md) |
| 2 | Engine hardening | M3 | `AgentEngine` 无 engine 事件、tool input/output 校验、token usage 聚合、context overflow 分类；`TurnRunner` 把异常包成裸 `IllegalStateException` | [`02-engine-hardening.md`](02-engine-hardening.md) |
| 3 | JsonSchema 类型扩展 | M3 | 只支持 string/int/number/bool/record/array；缺 Map、enum、optional/nullability、description | [`03-json-schema-extensions.md`](03-json-schema-extensions.md) |
| 4 | Memory 语义检索 | post-preview | `MemoryStore.search` 为关键字匹配，SPI 已预留替换点；缺向量化嵌入与相似度检索 | [`04-memory-semantic-retrieval.md`](04-memory-semantic-retrieval.md) |
| 5 | Authorizer / SecurityContext | M6 | `AgentRuntime` / HTTP 路由均无授权扩展点；agent instance id 不代表授权 | [`05-authorizer-security-context.md`](05-authorizer-security-context.md) |
| 6 | HTTP Client SDK | M6 | 无 `helm-client` 模块；外部 Java 应用只能直接拼 HTTP | [`06-http-client-sdk.md`](06-http-client-sdk.md) |
| 7 | Rate limiting / admission | post-preview | dispatch 与 prompt 同步执行，无并发上限、无配额、无排队 | [`07-rate-limiting.md`](07-rate-limiting.md) |
| 8 | Metrics & OpenTelemetry | M9 | 仅有 `helm-observability-logging`；无 metrics、无 trace 关联、无 content capture policy、无 redaction 注解 | [`08-metrics-opentelemetry.md`](08-metrics-opentelemetry.md) |
| 9 | Durable scale runtime | M11 | 同步执行；无 async workers、per-session FIFO 队列、lease/recovery、turn journal、stream chunk recovery、cancellation、provider routing/fallback、remote sandbox | [`09-durable-scale-runtime.md`](09-durable-scale-runtime.md) |
| 10 | Release engineering | M10 | 无 CHANGELOG/CONTRIBUTING/license 决策/Maven wrapper/CI/publishing/blueprints/adapter guides | [`10-release-engineering.md`](10-release-engineering.md) |
| 11 | API governance | M0 收尾 | 未写明 public/SPI/internal package 规则、pre-1.0 兼容策略、exception 稳定 code 与 safe details 规范 | [`11-api-governance.md`](11-api-governance.md) |

依赖关系（实现顺序建议）：

```text
11 API governance ──┐
                     ├─> 2 engine hardening ─> 1 streaming api ─┐
                     ├─> 3 json schema                          │
                     │                                          ├─> 5 authorizer ─> 6 client sdk
                     ├─> 8 metrics/otel <── 2 engine events ────┘
                     └─> 7 rate limiting ─> 9 durable scale ─> remote sandbox
                              │
                              └─> 4 memory semantic retrieval（可并行）
10 release engineering（横向贯穿，任意时点启动）
```

## 3. 架构图（更新版）

下图在 `README.md` 现有抽象调用图基础上，标注模块实现状态与未来缺口组件的落点。`✓` 已实现，`◯` 待实现（对应上方编号）。

### 3.1 分层架构

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ Application code                                                            │
│   AgentDefinition / WorkflowDefinition / Tool / Skill                       │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
          ┌────────────────────────┴────────────────────────┐
          │  集成层（可替换外围）                              │
          │                                                    │
          │  ┌─────────────┐  ┌──────────────┐  ┌──────────┐ │
          │  │ Spring Boot │  │ CLI (Picocli)│  │ HTTP SDK │ │
          │  │ starter  ✓   │  │          ✓   │  │  ◯ #6    │ │
          │  └──────┬──────┘  └──────┬───────┘  └────┬─────┘ │
          │         │                │                │       │
          │  ┌──────┴────────────────┴────────────────┴─────┐ │
          │  │ HTTP core (DTO/route/error)  ✓  + Servlet ✓  │ │
          │  │        + HelmAuthorizer ◯ #5  + rate limit ◯ #7│
          │  └──────────────────────┬─────────────────────────┘ │
          └─────────────────────────┼──────────────────────────┘
                                    │
┌───────────────────────────────────┴──────────────────────────────────────────┐
│  运行时层  helm-runtime  ✓                                                    │
│    AgentRuntime  (prompt / dispatch / inspection / session mgmt / memory 注入)│
│    WorkflowRuntime (invoke / getRun / listRuns / events)                       │
│    registries (agent/workflow/tool/skill/provider)                             │
│    EventRedactor + EventBus + InMemoryRuntimeStore + FakeProvider             │
│    admission / rate limiting ◯ #7  →  durable queue ◯ #9                       │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    │
┌───────────────────────────────────┴──────────────────────────────────────────┐
│  执行引擎层  helm-agent-engine  ✓（基础） / ◯ #2 hardening                     │
│    AgentLoop / TurnRunner / ToolCallOrchestrator / ToolExecutor               │
│    ModelStreamNormalizer  +  streaming 暴露 ◯ #1                             │
│    engine events + tool validation + usage aggregation + overflow 分类 ◯ #2  │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    │
┌───────────────────────────────────┴──────────────────────────────────────────┐
│  契约层  helm-core  ✓                                                         │
│    AgentDefinition / WorkflowDefinition / Tool / Skill / Sandbox /           │
│    ModelProvider / RuntimeStore / MemoryStore / 事件 / 错误(HelmException)     │
│    JsonSchema ✓（基础） / ◯ #3 扩展    MemoryStore.search ◯ #4 语义检索      │
│    HelmSecurityContext / HelmAuthorizer SPI ◯ #5                              │
└──────────────────────────────────────────────────────────────────────────────┘

  横切适配器（独立模块，只依赖稳定 SPI）：
    helm-provider-openai  ✓     helm-provider-anthropic  ✓
    helm-sandbox-local  ✓      helm-persistence-jdbc  ✓（RuntimeStore + MemoryStore）
    helm-observability-logging  ✓   helm-observability-opentelemetry  ◯ #8
    remote / container sandbox  ◯ #9
```

### 3.2 目标模块图

`✓` 已实现，`◯` 待实现。

```text
helm/
  helm-core/                      ✓   契约层（+ authorizer SPI #5、json schema 扩展 #3）
  helm-agent-engine/              ✓   执行引擎（+ hardening #2、streaming 暴露 #1）
  helm-runtime/                   ✓   运行时（+ admission/rate limit #7、durable #9）
  helm-runtime-testkit/           ◯   #10 提供（合约测试集中模块）
  helm-provider-openai/           ✓
  helm-provider-anthropic/       ✓
  helm-persistence-jdbc/          ✓
  helm-sandbox-local/             ✓
  helm-sandbox-remote/            ◯   #9
  helm-http-core/                 ✓
  helm-http-servlet/              ✓
  helm-client/                    ◯   #6
  helm-cli/                       ✓
  helm-spring-boot-starter/       ✓
  helm-observability-logging/     ✓
  helm-observability-opentelemetry/ ◯ #8
  helm-memory-semantic/           ◯   #4（可选独立模块，或并入 persistence 适配器）
  examples/
  docs/
```

### 3.3 调用链（含未来组件）

```text
Application
  → Client SDK #6 / HTTP Servlet / CLI
    → [HelmAuthorizer #5] [RateLimiter #7]
      → AgentRuntime.dispatch / prompt
        → OperationRecord admission (RuntimeStore)
          → AgentEngine #2
            → TurnRunner → ModelProvider.stream
              → engine events → RuntimeEventObserver (logging ✓ / OTel #8 / metrics #8)
            → ToolCallOrchestrator → ToolExecutor → Tool
            → ContextManager (overflow 分类 #2) / usage 聚合 #2
          → [durable queue #9: lease / journal / recovery]
        → AgentSessionState 持久化
        → streaming 暴露 #1 → 调用方
```

## 4. 统一设计规范

所有组件设计文档遵循以下规范，保证风格一致、可独立评审、可平滑进入实施。

### 4.1 文档结构

每篇组件设计文档采用统一章节：

1. **背景与目标** — 为什么需要、解决什么问题、不解决什么。
2. **现状与缺口** — 当前代码/设计缺什么，引用具体文件与 roadmap 出处。
3. **设计方案** — SPI/接口定义、模块归属、API 形态（含 Java 代码片段）、数据结构、配置项。
4. **数据流与时序** — 关键路径的调用时序、事件序列、并发与失败处理。
5. **安全与边界** — 默认值、脱敏、能力收窄、错误映射、依赖守则。
6. **测试策略** — 契约测试、单测、集成测试、FakeProvider/Fake 实现约定。
7. **验收标准** — 可勾选的 exit criteria。
8. **风险与未决项** — 显式列出未定决策，不假装已解决。
9. **与其他组件的关系** — 依赖、被依赖、命名对齐。

### 4.2 设计守则（来自 roadmap 第 2 节）

- **Core-first**：core 不依赖 runtime/engine/HTTP/CLI/Spring/provider SDK/JDBC/logging。
- **Contract-first**：可替换能力先定义稳定 SPI + 合约测试，再实现 adapter。
- **Agent 与 Workflow 分离**：不混用 operation id / dispatch id / workflow run id。
- **Admission 优先**：prompt/dispatch/workflow invoke 先形成可检查记录，再执行或排队。
- **事件优先**：HTTP/CLI/SDK/log/metrics/tracing 基于同一套 runtime event taxonomy。
- **安全默认**：HTTP 默认关闭、shell 默认关闭、credentials 只服务端读取、events/logs 默认脱敏。
- **应用拥有业务权限**：Helm 提供 authorizer/security context 扩展点；agent instance id ≠ 授权。
- **窄工具边界**：Tool 暴露最小业务能力。
- **本地沙箱不是生产隔离**。
- **可验证发布**：每个模块必须有测试、文档、示例。

### 4.3 命名与依赖约定

- 生产 Java 包命名空间：`io.agent.helm`。
- SPI 放 `helm-core` 对应子包（`core.model`、`core.store`、`core.memory`、`core.event`、`core.security` …）。
- 内部实现放 `helm-runtime` / `helm-agent-engine`，包内可见（package-private 优先）。
- Adapter 模块只依赖 `helm-core` 的稳定 SPI，不依赖 runtime internals。
- 新增 SPI 必须配 `ContractTest` 抽象基类（发布 test-jar），in-memory 与 JDBC/真实 adapter 共用。
- 错误统一走 `HelmException(code, message, details, developerDetails)`，code 为稳定 `SCREAMING_SNAKE_CASE`，details 可安全暴露，developerDetails 不进 events/logs/safe errors。

### 4.4 验证约定

设计阶段不要求编译，但任何代码片段必须：

- 包名、类型名与现有代码对齐（参见 `helm-core/src/main/java/io/agent/helm/core/**`）。
- 不引入 core 禁止依赖（Spring/Servlet/JDBC/SDK/logging）。
- 新 SPI 必须给出契约测试要点。

实施阶段统一验证命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

## 5. 如何使用本目录

- **评审某一组件**：直接读对应编号文档，每篇自洽。
- **理解全局**：先读本 README 第 2、3 节，再按依赖关系（第 2 节末图）阅读。
- **进入实施**：选某一组件文档，基于其第 7 节验收标准 + 第 4 节设计，在 `docs/superpowers/plans/` 新建实施计划。
- **更新进度**：实施完成后回写 `docs/roadmap.md` 第 7 节进度日志，并把对应缺口行从 ◯ 改为 ✓。
