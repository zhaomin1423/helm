# Helm 整体系统设计方案与里程碑规划

本文档基于项目定位，给出 Helm 的整体系统设计方案、阶段拆分与里程碑规划。它是 `docs/helm-mvp-design.md` 的路线图补充：MVP 设计文档定义"是什么"，本文档定义"分几个阶段、每个阶段交付什么、如何验收"。

## 一、项目定位

Helm 是面向 Java 生态的 **Agent Harness Framework**：模型不是孤立的聊天 API，而是运行在一个可编程 harness 中，通过 session、tool、skill、sandbox、workflow、事件流和持久化完成真实工作。

设计路线为 **Core-first**：运行时核心不依赖 Spring、Servlet、Provider SDK、JDBC 等任何具体基础设施，所有外部能力通过稳定 SPI 接入。

## 二、整体系统架构（分层）

自内向外分为四层：

### 1. 契约层（`helm-core`）

- 稳定的公开 API 与 SPI：`AgentDefinition`、`WorkflowDefinition<I, O>`、`Tool<I, O>`、`SkillDefinition`、`Sandbox`、`ModelProvider`、`RuntimeStore`、消息与内容块、结构化错误（`HelmException` 体系与稳定错误码）、类型描述与 JSON Schema。
- 只包含小的不可变记录/值对象和接口，是所有外层模块的唯一依赖锚点。

### 2. 执行引擎层（`helm-agent-engine`）

- Agent loop 与 turn 执行：模型调用、流事件规范化、tool-call 编排、停止条件、上下文溢出处理与 compaction。
- 无持久化、无组件发现，只执行"准备好的"请求，保持可独立测试。

### 3. 运行时层（`helm-runtime`）

- 注册中心（agent / workflow / tool / skill / provider）、harness 与 session 生命周期、operation 与 workflow run 记录、事件总线与事件脱敏、`RuntimeStore` 抽象及内存实现、`FakeProvider` 用于确定性测试。

### 4. 集成层（可替换外围模块）

| 领域 | 模块 | 职责 |
| --- | --- | --- |
| Provider | `helm-provider-openai`、`helm-provider-anthropic` | 真实模型 provider 适配器 |
| Sandbox | `helm-sandbox-local` | 内存 / 本地文件系统 / 受控 shell |
| 传输 | `helm-http-core` + `helm-http-servlet` | 框架无关 DTO 与 route spec + Servlet adapter |
| 开发工具 | `helm-cli` | Picocli：`helm dev`、`helm run`、运行记录检查 |
| 框架集成 | `helm-spring-boot-starter` | 自动配置、Bean 发现、properties 绑定 |
| 持久化 | `helm-persistence-jdbc` | JDBC runtime store + Flyway 迁移 |
| 可观测性 | `helm-observability-logging` | 事件日志 observer，后续接 OpenTelemetry |

### 横切设计原则

1. 显式能力注册：Tool、Skill、Provider、Sandbox 都必须显式注册。
2. 安全默认值：默认禁用 shell，HTTP 暴露必须显式开启，tool 输入需要校验。
3. 一切执行产生结构化事件：run、operation、tool call、provider call、错误都可观测。
4. SPI 兼容性由契约测试保障：每个 SPI 提供可复用的契约测试套件，所有实现必须通过。

## 三、阶段拆分与里程碑

### Milestone 1：Core Runtime（已完成）

- 交付：`helm-core`、`helm-agent-engine`、`helm-runtime`，含 `AgentLoop`、`TurnRunner`、`AgentHarness`、`AgentSession`、typed `Tool`、`FakeProvider`、`InMemoryRuntimeStore`，以及证明 agent prompt 与 workflow 执行的确定性测试。
- 出口条件：`mvn verify` 全绿，核心不含任何框架依赖。
- 实施计划：`docs/superpowers/plans/2026-06-28-helm-mvp-milestone-1.md`

### Milestone 2：Provider、Skill、Sandbox

- 交付：
  1. OpenAI 兼容 provider（含流式响应规范化到 core 的 stream 事件模型）。
  2. Anthropic provider。
  3. Skill 加载机制：从 classpath 加载可复用指令与文件包。
  4. In-memory sandbox 与 local sandbox（受控文件系统 + 可选 shell，默认关闭）。
- 验证：Provider SPI 与 Sandbox SPI 契约测试；用 WireMock 做 provider 协议测试，不引入真实凭证与网络依赖测试。
- 出口条件：同一个 agent 定义可在 `FakeProvider` 与真实 provider 适配器之间无代码变更切换。
- 实施计划：`docs/superpowers/plans/2026-07-02-helm-mvp-milestone-2.md`

### Milestone 3：HTTP 与 CLI

- 交付：
  1. `helm-http-core`：agent prompt / dispatch route、workflow invoke route、operation 与 workflow run 检查 route、统一 HTTP error response 结构。
  2. `helm-http-servlet`：Servlet adapter。
  3. `helm-cli`：`helm dev`（本地开发服务器）、`helm run`（本地调用 workflow）、运行记录检查命令。
- 验证：HTTP error 结构契约测试；CLI 端到端跑通 examples。
- 出口条件：可以通过 HTTP 与 CLI 两条路径运行持久 session 与有限 workflow。
- 实施计划：`docs/superpowers/plans/2026-07-02-helm-mvp-milestone-3.md`

### Milestone 4：Spring Boot Starter

- 交付：自动配置、组件（Agent / Workflow / Tool / Provider Bean）发现、`application.yml` properties 绑定、Spring Boot 示例应用。
- 约束：Starter 仅做装配，core 保持零 Spring 依赖。
- 出口条件：一个最小 Spring Boot 应用只需声明 Bean 即可暴露 agent 与 workflow。
- 实施计划：`docs/superpowers/plans/2026-07-02-helm-mvp-milestone-4.md`

### Milestone 5：JDBC 持久化与可观测性

- 交付：
  1. `helm-persistence-jdbc`：JDBC `RuntimeStore` 实现 + Flyway schema 迁移。
  2. Runtime event 持久化。
  3. `helm-observability-logging`：日志 observer（SLF4J），为后续 OpenTelemetry 预留接口。
- 验证：`RuntimeStore` SPI 契约测试同时跑内存实现与 JDBC 实现（嵌入式数据库）。
- 出口条件：进程重启后 session、operation、workflow run、event 历史可恢复检查。
- 实施计划：`docs/superpowers/plans/2026-07-02-helm-mvp-milestone-5.md`

### Milestone 6（MVP 后展望，非 MVP 范围）

- 分布式调度。
- 多租户与权限体系（`HelmAuthorizer` 扩展）。
- 生产级容器隔离 sandbox。
- 跨进程 durable execution。
- Channel 生态与插件机制。

## 四、里程碑推进方式

- 每个里程碑独立成 plan（沿用 `docs/superpowers/plans/` 的方式），M2 起各集成模块可并行：Provider 与 Sandbox 互不依赖；M3 的 `helm-http-core` 只依赖 M1，可以提前开始。
- 每个里程碑的统一验收标准：
  1. `mvn verify` 全绿（编译 + 单测 + Spotless）。
  2. 新增 SPI 有契约测试。
  3. examples 或文档同步更新。
  4. core 三模块（`helm-core`、`helm-agent-engine`、`helm-runtime`）依赖边界不被破坏。

## 五、依赖关系与并行策略

```text
M1 Core Runtime（已完成）
 ├── M2a Provider（OpenAI / Anthropic）  ── 可并行
 ├── M2b Skill 加载                      ── 可并行
 ├── M2c Sandbox（in-memory / local）    ── 可并行
 ├── M3a helm-http-core                  ── 可并行（仅依赖 M1）
 │     └── M3b helm-http-servlet
 │           └── M3c helm-cli（helm dev 依赖 servlet adapter）
 │                 └── M4 Spring Boot Starter（依赖 http-core 暴露 route）
 └── M5a helm-persistence-jdbc           ── 可并行（仅依赖 RuntimeStore SPI）
       └── M5b helm-observability-logging
```
