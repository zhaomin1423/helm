# Helm MVP Milestone 3 Design Plan: HTTP 与 CLI

> **Status:** 设计文档，尚未实施。实施前可按需细化为逐任务的可执行计划。

**Goal:** 提供通过 HTTP 与 CLI 两条路径运行持久 agent session 与有限 workflow 的能力，同时保持 runtime core 与任何 Web 框架解耦。

**Architecture:** `helm-http-core` 定义与框架无关的 request/response DTO、route spec 与 handler 抽象，只依赖 `helm-core` 与 `helm-runtime`。`helm-http-servlet` 是唯一接触 Servlet API 的模块。`helm-cli` 基于 Picocli，复用 runtime 与 servlet adapter（内嵌轻量 Servlet 容器用于 `helm dev`）。

**Tech Stack:** Java 21、Maven、Jackson、Picocli、Jetty（embedded，仅 `helm-cli`/测试范围）、JUnit 5、AssertJ。

---

## Scope

- 新模块：`helm-http-core`、`helm-http-servlet`、`helm-cli`。
- 不包含：认证授权的完整实现（预留 `HelmAuthorizer` 挂点）、WebSocket/SSE 之外的推送协议、生产部署配置。

## Task 1: `helm-http-core` route spec 与 DTO

- 定义 `HttpRoute`、`HttpHandler`、`HttpRequest`/`HttpResponse` 抽象（不依赖 Servlet 类型）。
- Routes：
  1. `POST /agents/{agent}/instances/{instance}/sessions/{session}/prompt`
  2. `POST /agents/{agent}/dispatch`
  3. `POST /workflows/{workflow}/invoke`
  4. `GET /operations/{id}`、`GET /sessions/{id}/operations`
  5. `GET /workflow-runs/{id}`、`GET /workflows/{workflow}/runs`
- 统一 HTTP error response 结构：`{ "error": { "code", "message", "details" } }`，由 `HelmException` 错误码稳定映射。
- HTTP 暴露默认关闭，需显式注册 route。

## Task 2: HTTP error 契约测试

- 契约测试套件覆盖：未知 agent/workflow、输入校验失败、provider 错误、内部错误各自的状态码与 error body 结构。
- 该套件将被 servlet adapter 与未来任何 transport adapter 复用。

## Task 3: `helm-http-servlet` adapter

- 单一 `HelmServlet` 将 Servlet request/response 与 `helm-http-core` 抽象互转。
- 流式响应（prompt 的事件流）通过 chunked response / SSE 输出。
- 测试：内嵌 Jetty 跑通全部 route 与 error 契约测试。

## Task 4: `helm-cli` 骨架与 `helm run`

- Picocli 主命令 `helm`，子命令 `run <workflow> --input <json>`：本地构造 runtime、执行 workflow、输出结果与退出码（成功 0，失败非 0 且打印结构化错误）。
- 组件发现：MVP 阶段通过显式 `HelmApp` 装配类（用户提供 main 配置入口），不做类路径扫描魔法。

## Task 5: `helm dev` 与运行记录检查

- `helm dev`：启动内嵌 Jetty + `HelmServlet`，暴露已注册 route，默认只绑定 localhost。
- 检查命令：`helm operations <session-id>`、`helm runs <workflow>`、`helm run-detail <run-id>`，从 `RuntimeStore` 读取并以表格/JSON 输出。

## Task 6: 端到端示例

- `examples/` 增加一个通过 CLI 跑通的示例 workflow，并在 CI 可执行的集成测试中覆盖（使用 `FakeProvider`）。

## 验收标准

1. `mvn verify` 全绿。
2. HTTP error 结构契约测试在 servlet adapter 上通过。
3. CLI 端到端跑通 examples（`helm run` 与 `helm dev` + HTTP 调用）。
4. `helm-http-core` 不依赖 Servlet；core 三模块不依赖任何新增模块。
