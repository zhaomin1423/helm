# Changelog

All notable changes to Helm are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/) with the
pre-1.0 convention described in [docs/design/11-api-governance.md](docs/design/11-api-governance.md).

## [Unreleased]

### Added

- 生产化组件落地（2026-07-05，对应 `docs/design/01-11`）：11 个生产化设计方案已基本
  实现（#9 durable scale 为部分实现），`mvn verify` 全绿（22 模块、809 测试、Spotless
  clean）。
  - `helm-core`：`SessionConflictException`（`SessionStore.saveSession` OCC）；`Store` SPI
    分页查询重载；`WorkQueue.complete` 强制终态 `OperationStatus`（新增 `CANCELLED`/
    `INTERRUPTED`）；`JsonSchema` 支持 `UUID`/`Instant`/`BigDecimal`/`Set`/temporal/
    嵌套 `Optional`；扩展 `ToolContext`（`securityContext`/`sandbox`/`clock`/
    `ToolLogger`）；`ModelStreamEvent.Completed.finishReason` + `TokenUsage`
    cache/reasoning 字段；`HelmAction` 新增 `TOOL_EXECUTE`/`MEMORY_WRITE`/
    `SANDBOX_COMMAND`；`Sandbox.disabled()` 默认；`RuntimeStore` 拆分为 `SessionStore`/
    `OperationStore`/`WorkflowRunStore`/`EventStore` 子接口；`@Preview`/`@Experimental`
    注解 + `ErrorCode.stable()`；`WorkflowException`。
  - `helm-agent-engine` / `helm-runtime`：流式失败事件 `ModelFailed`/`ToolFailed`/
    `TurnFailed`；`AgentRuntime` 实现 `AutoCloseable` 并支持 `durable` 模式（`@Preview`），
    内含 `LeaseManager`（按 operationId 原子 claim、幂等 recovery、recover-before-requeue）；
    `InMemoryWorkQueue` 原子化；`WorkflowRuntime` 将 `authorizer`/`rateLimiter`/
    `memoryStore` 透传至内层 runtime。
  - `helm-memory-semantic`：`IndexedEmbeddingStore` SPI（将 `SemanticMemoryStore` 与
    `InMemoryEmbeddingStore` 解耦）；embedding 维度校验。
  - `helm-client`：`HelmClient` / `JdkHttpTransport` 实现 `AutoCloseable`；增量式
    `promptStream`（`BodyHandlers.ofPublisher` + 行订阅）；`invokeWorkflow` 类型化输出
    （`Class<O>` / `TypeReference<O>`）；RFC 3986 path-segment 编码。
- 系统设计 M1：`helm-core` 契约层、`helm-agent-engine` 执行引擎、`helm-runtime` 运行时，
  含 `FakeProvider`、`InMemoryRuntimeStore`、基础事件 redaction。
- 系统设计 M2：`helm-provider-openai`、`helm-provider-anthropic` provider 适配器；
  skill loading；`helm-sandbox-local`（InMemory + Local 两种实现）。
- 系统设计 M3：`helm-http-core` framework-neutral HTTP 层、`helm-http-servlet`
  Jakarta Servlet 适配器、`helm-cli`（`helm dev` / `helm run` / `helm inspect`）。
- 系统设计 M4：`helm-spring-boot-starter` 自动配置、bean discovery、conditional HTTP
  route registration、`examples/spring-boot-example`。
- 系统设计 M5：`helm-persistence-jdbc`（`JdbcRuntimeStore` + `JdbcMemoryStore`），
  Flyway schema migrations（`V1__init.sql`、`V2__memory.sql`），event persistence，
  `helm-observability-logging`（`LoggingRuntimeObserver`）。
- 缺口补齐（2026-07-05）：`MemoryStore` SPI 与内置 `save_memory` tool，记忆按 agent
  实例 scope 存储并自动注入 instructions；session 生命周期管理（列出、检查、重置），
  `maxSessionMessages` 历史裁剪。
- 生产化设计文档：streaming API（#1）、engine hardening（#2）、JsonSchema extensions
  （#3）、memory 语义检索（#4）、authorizer security context（#5）、HTTP client SDK
  （#6）、rate limiting（#7）、metrics / OpenTelemetry（#8）、durable scale runtime
  （#9）、release engineering（#10）、API governance（#11）共 11 篇组件设计方案。
- Release engineering 基线：Apache License 2.0、Maven wrapper、GitHub Actions CI、
  `CHANGELOG.md`、`CONTRIBUTING.md`、adapter 实现指南。

### Changed

- 项目 license 决策为 Apache License 2.0（见 `LICENSE`）。
- `docs/design/01-11` 的 11 个生产化组件设计方案现已基本落地实现（#9 durable scale
  为部分实现，其余已实现基础版本），详见 `docs/roadmap.md` 与各模块源码。

### Fixed

- 全代码库审查修复（2026-07-05，共 9 个 commit），按模块汇总：
  - `helm-runtime`：`promptStream` session-lock 竞态（finally 仅释放本任务持有的 entry）；
    `processOperation` 按 operationId claim（原先会 claim 错队列项）；lease recovery
    幂等（跳过终态 operation）；流超时不再被上报为成功。
  - `helm-provider-openai`：从 `instructions` 发出 system message（原先被丢弃）；拆分
    多 `ToolResultBlock` 消息；检测 SSE `error` 事件；订阅者取消信号传递；tool-schema
    保真度（description/enum/nullable）。
  - `helm-sandbox-local`：空 env-allowlist 表示"不继承任何环境变量"；超时时杀进程树；
    TOCTOU 软链缓解；`[truncated]` 截断标记。
  - `helm-http-*` / `helm-cli` / `helm-spring-boot-starter`：请求体大小上限；
    `routePrefix` 使用 `getPathInfo`；`authorize()` 在错误信封内执行；可伪造的
    `X-Helm-Principal` 不再自动装配；运行时 Bean 加 `@ConditionalOnMissingBean`；
    SSE 失败 → 非 200；`helm dev` 优雅停机；`PromptCommand` 超时 → exit 1。
  - `helm-persistence-jdbc`：`saveSession` OCC；`appendEvent` 在 unique-violation 上
    幂等；LIKE 通配符转义；保留 `SQLException` cause + `retryable` 标志；OTel 孤儿
    span TTL 扫描 + 上限 + `AutoCloseable` drain；`ContentCaptureLevel` SUMMARY/FULL
    落地。

## Notes

- 首个正式发布版本号将为 `0.1.0`。当前所有内容仍在 `[Unreleased]` 段，发布时将其
  改为 `[0.1.0] - <date>` 并新建空 `[Unreleased]` 段。
- 每个 PR 必须在 `[Unreleased]` 下追加对应条目（`Added` / `Changed` / `Fixed` /
  `Security`），条目格式为 `<模块名>: <一句话描述>`。

[Unreleased]: https://github.com/agent-helm/helm/compare/HEAD
