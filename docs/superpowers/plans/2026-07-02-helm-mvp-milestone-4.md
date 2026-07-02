# Helm MVP Milestone 4 Design Plan: Spring Boot Starter

> **Status:** 设计文档，尚未实施。实施前可按需细化为逐任务的可执行计划。

**Goal:** 提供 `helm-spring-boot-starter`，使一个最小 Spring Boot 应用只需声明 Bean 即可暴露 agent 与 workflow，而 runtime core 保持零 Spring 依赖。

**Architecture:** Starter 仅做装配：发现 `AgentDefinition`、`WorkflowDefinition`、`Tool`、`ModelProvider`、`Sandbox`、`RuntimeStore` 类型的 Bean，注册进 helm runtime 注册中心，并按配置把 `helm-http-core` route 挂到 Spring 的 Servlet 容器（复用 `helm-http-servlet`）。禁止在 starter 中实现任何 runtime 行为。

**Tech Stack:** Java 21、Maven、Spring Boot 3.x（仅 starter 模块与示例）、JUnit 5、AssertJ、Spring Boot Test。

---

## Scope

- 新模块：`helm-spring-boot-starter`、`examples/spring-boot-example`。
- 不包含：Spring WebFlux 支持、Spring Security 集成（预留 `HelmAuthorizer` Bean 挂点）。

## Task 1: 自动配置骨架

- `HelmAutoConfiguration` + `AutoConfiguration.imports` 注册。
- 构建 `HelmRuntime` Bean：默认 `InMemoryRuntimeStore` 与事件总线，允许用户 Bean 覆盖（`@ConditionalOnMissingBean`）。

## Task 2: 组件发现

- 收集应用上下文中所有 `AgentDefinition`、`WorkflowDefinition<?, ?>`、`Tool<?, ?>`、`ModelProvider`、`SkillDefinition`、`Sandbox` Bean 并注册。
- 名称冲突时启动失败并给出结构化错误信息（fail-fast）。

## Task 3: `application.yml` properties

- `helm.*` 配置前缀（`@ConfigurationProperties`）：
  1. `helm.http.enabled`（默认 `false`，安全默认值）与 route 前缀。
  2. `helm.provider.*`：provider 选择与 base URL、超时；凭证仅支持从环境变量/外部化配置引用。
  3. `helm.sandbox.*`：sandbox 类型与根目录，shell 默认禁用。
- 配置元数据（`spring-configuration-metadata.json`）供 IDE 提示。

## Task 4: HTTP 暴露

- 当 `helm.http.enabled=true` 时，将 `helm-http-core` route 通过 `helm-http-servlet` 的 `HelmServlet` 注册为 `ServletRegistrationBean`。
- 复用 Milestone 3 的 HTTP error 契约测试，在 Spring Boot Test（`@SpringBootTest` + 随机端口）下通过。

## Task 5: 示例应用

- `examples/spring-boot-example`：声明一个 agent、一个 workflow、一个 tool，配置 `FakeProvider`，通过 HTTP 调用演示 prompt 与 workflow invoke。
- 示例作为集成测试纳入 `mvn verify`。

## 验收标准

1. `mvn verify` 全绿。
2. core 三模块与 http/cli 模块无任何 Spring 依赖（可用 Maven Enforcer banned dependencies 校验）。
3. 最小示例应用只包含 Bean 声明与 `application.yml`，即可运行 agent 与 workflow。
