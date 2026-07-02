# Helm MVP Milestone 5 Design Plan: JDBC 持久化与可观测性

> **Status:** 设计文档，尚未实施。实施前可按需细化为逐任务的可执行计划。

**Goal:** 提供 JDBC `RuntimeStore` 实现与 schema 迁移，使 session、operation、workflow run 与 runtime event 在进程重启后可恢复检查；并提供日志 observer 作为第一个可观测性适配器。

**Architecture:** `helm-persistence-jdbc` 只依赖 `helm-core`（`RuntimeStore` SPI）、JDBC 与 Flyway，不依赖 Spring 或任何连接池实现（`DataSource` 由使用方注入）。`helm-observability-logging` 只依赖 `helm-core` 事件类型与 SLF4J API。两个模块都必须通过与内存实现相同的契约测试。

**Tech Stack:** Java 21、Maven、JDBC、Flyway、H2（仅测试）、SLF4J API、JUnit 5、AssertJ。

---

## Scope

- 新模块：`helm-persistence-jdbc`、`helm-observability-logging`。
- 不包含：跨进程 exactly-once durable execution、分布式锁、OpenTelemetry 实现（仅预留接口形状）。

## Task 1: `RuntimeStore` SPI 契约测试套件

- 抽象契约测试基类覆盖：session/operation/workflow run/event 的写入、按 ID 与按父级查询、事件顺序保证、并发追加、不存在 ID 的错误行为。
- `InMemoryRuntimeStore` 必须首先通过该套件（回填基准）。

## Task 2: Schema 与 Flyway 迁移

- 表设计：`helm_session`、`helm_operation`、`helm_workflow_run`、`helm_event`（含单调序号列保证事件顺序）。
- 负载（消息、tool 输入输出）以 JSON 文本列存储，schema 版本随迁移演进。
- `V1__init.sql` 起步；迁移由模块内 Flyway API 触发，也允许使用方自管迁移。

## Task 3: `JdbcRuntimeStore` 实现

- 纯 JDBC 实现（`DataSource` 注入），每个写入操作事务化。
- SQL 异常映射到结构化 `HelmException` 子类（唯一键冲突、找不到记录等有稳定错误码）。
- 测试：H2（内存 + 文件模式）跑完整契约测试套件；文件模式验证"重启后可恢复"（关闭旧连接并重新创建 `DataSource` 后验证数据仍在）。

## Task 4: Runtime event 持久化路径

- 确认 `helm-runtime` 事件总线到 `RuntimeStore` 的持久化路径在 JDBC 实现下语义不变（顺序、脱敏在持久化之前完成）。
- 增加针对脱敏后事件落库的集成测试。

## Task 5: `helm-observability-logging`

- `LoggingRuntimeObserver`：订阅 runtime event 并按事件类型输出结构化日志（SLF4J，参数化消息，不字符串拼接）。
- 日志级别策略：生命周期事件 INFO、tool/provider 调用 DEBUG、错误 WARN/ERROR。
- 敏感字段依赖 runtime 的事件脱敏，observer 不做二次处理但有测试验证不泄露原始凭证字段。

## Task 6: 文档与示例

- 文档：JDBC store 接入方式（DataSource 注入、迁移策略）、日志 observer 启用方式。
- Spring Boot 示例增加可选的 JDBC + logging 配置演示（复用 Milestone 4 示例）。

## 验收标准

1. `mvn verify` 全绿。
2. `RuntimeStore` 契约测试同时在 `InMemoryRuntimeStore` 与 `JdbcRuntimeStore`（H2）上通过。
3. 进程重启（文件模式 H2）后 session、operation、workflow run、event 历史可查询。
4. 测试不依赖外部数据库服务。
