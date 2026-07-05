# Helm

> 面向 Java 生态的 Agent Harness Framework。

Helm 用 Java 构建可编程的 AI Agent 运行时。它不是一个简单的 LLM SDK 包装器，而是为模型提供完整 harness：session、tool、skill、sandbox、workflow、事件流、持久化和可替换的模型 provider。

项目当前处于 **MVP 设计阶段**。完整设计见 [`docs/helm-mvp-design.md`](docs/helm-mvp-design.md)。

## 为什么是 Helm？

大模型本身只能接收输入并生成输出。要让它完成真实任务，还需要一个运行环境：它要能保留上下文、调用受控工具、读写文件、在安全边界内执行命令，并把每次运行记录下来。

Helm 的目标是把这些能力做成 Java 开发者熟悉的框架：

- **Agent**：适合持续会话、外部事件和长期上下文。
- **Workflow**：适合一次性、可检查、有明确输入输出的后台任务。
- **Tool**：把数据库、业务服务和外部 API 封装成模型可调用的窄能力。
- **Skill**：把可复用经验、流程和说明打包给 Agent 使用。
- **Sandbox**：为文件系统和 shell 操作提供受控边界。
- **Memory**：把跨 session 的长期记忆（用户偏好、稳定事实）存储在可替换的 `MemoryStore` 中，并自动注入后续对话。
- **Session 管理**：持久化会话历史，支持恢复、列出、检查和重置 session，并可限制历史长度。
- **Provider SPI**：用统一接口接入 OpenAI、Anthropic、本地模型或企业网关。

## 特性规划

| 能力 | MVP 规划 |
| --- | --- |
| Core-first runtime | 核心运行时不绑定 Spring、Servlet、Netty 或其他 Web 框架。 |
| Agent engine | 内置 `helm-agent-engine`，负责 agent loop、turn、stream、tool-call 编排和 compaction。 |
| Typed tools | 使用 Java 类型定义 tool 输入输出，并生成模型可理解的 schema。 |
| Persistent sessions | 保存 Agent session、operation、消息历史和事件。 |
| Session management | 列出、检查、重置 session；`maxSessionMessages` 限制上下文长度。 |
| Long-term memory | `MemoryStore` SPI + 内置 `save_memory` tool，记忆按 agent 实例 scope 存储并自动注入 instructions。 |
| Finite workflows | 支持有限任务运行、状态查询、结果返回和事件检查。 |
| Replaceable providers | 通过 `ModelProvider` SPI 接入不同模型供应商。 |
| Sandbox SPI | 支持内存 sandbox 和本地开发 sandbox，后续可扩展到容器或远程 sandbox。 |
| Spring Boot starter | 提供自动配置、bean discovery 和 HTTP route 挂载。 |
| CLI | 提供 `helm dev`、`helm run`、`helm inspect` 等本地开发命令。 |

## 快速示例

> 以下 API 是 MVP 设计目标，实际实现可能随开发推进调整。

定义一个 Agent：

```java
public final class AssistantAgent implements AgentDefinition {
    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
            .model("openai/gpt-4.1")
            .instructions("You are a helpful assistant.")
            .tool(new WeatherTool())
            .sandbox(Sandboxes.inMemory())
            .build();
    }
}
```

定义一个 Workflow：

```java
public final class ReviewWorkflow implements WorkflowDefinition<ReviewInput, ReviewOutput> {
    @Override
    public WorkflowConfig config() {
        return WorkflowConfig.builder()
            .agent(new AssistantAgent())
            .build();
    }

    @Override
    public ReviewOutput run(WorkflowContext<ReviewInput> context) throws Exception {
        AgentSession session = context.harness().session("default");
        PromptResult result = session.prompt("Review this document: " + context.input().content());
        return new ReviewOutput(result.text());
    }
}
```

计划中的本地运行方式：

```bash
helm run review --input '{"content":"..."}'
helm dev
```

## 架构概览

模块实现状态：`✓` 已实现，`◯` 待实现（详见 [`docs/design/README.md`](docs/design/README.md)）。

```text
Application code
  -> Client SDK ◯ / HTTP Servlet ✓ / CLI ✓
    -> [HelmAuthorizer ◯] [RateLimiter ◯]
      -> AgentRuntime ✓ / WorkflowRuntime ✓
        -> registries (agent/workflow/tool/skill/provider) ✓
        -> AgentEngine ✓（基础）/ hardening ◯
          -> TurnRunner -> ModelProvider.stream ✓
          -> ToolCallOrchestrator / ToolExecutor ✓
        -> RuntimeStore ✓ / MemoryStore ✓ / EventBus ✓
        -> admission / rate limiting ◯ -> durable queue ◯
```

模块结构（`✓` 已实现，`◯` 待实现，对应 [`docs/design/`](docs/design/) 各组件方案）：

```text
helm/
  helm-core/                       ✓  契约层（+ authorizer SPI ◯、json schema 扩展 ◯）
  helm-agent-engine/               ✓  执行引擎（+ hardening ◯、streaming 暴露 ◯）
  helm-runtime/                    ✓  运行时（+ admission/rate limit ◯、durable ◯）
  helm-provider-openai/            ✓
  helm-provider-anthropic/         ✓
  helm-sandbox-local/              ✓
  helm-sandbox-remote/             ◯
  helm-http-core/                  ✓
  helm-http-servlet/               ✓
  helm-client/                     ◯
  helm-cli/                        ✓
  helm-spring-boot-starter/        ✓
  helm-persistence-jdbc/           ✓
  helm-observability-logging/      ✓
  helm-observability-opentelemetry/ ◯
  examples/
  docs/design/                     组件级设计方案（11 篇）
```

## Agent Engine 边界

Helm 使用第一方 `helm-agent-engine` 承担模型运行时的核心职责，并通过稳定的 Java API 暴露清晰边界：

- `AgentLoop`
- `TurnRunner`
- `ModelStreamEvent`
- `ToolCallOrchestrator`
- `ContextManager`
- `TokenUsage`
- `ContextOverflowException`

这些类型共同覆盖 agent loop、消息模型、streaming、tool calling、usage 统计和上下文溢出处理，使 Helm 在保持 harness-first 架构思想的同时，拥有稳定、自然的 Java API。

## 设计原则

1. **核心稳定**：`helm-core` 只定义 Helm 自己的公开类型和 SPI。
2. **外部隔离**：模型 SDK、数据库、Web 框架、Sandbox 服务都放在 adapter 模块中。
3. **能力收窄**：模型只能调用应用显式注册的 tool，不能直接接触宽泛客户端或凭据。
4. **安全默认**：shell 默认关闭，HTTP 暴露默认关闭，事件日志需要避免 secret。
5. **可观测**：每个 run、operation、turn、tool call、provider call 都应产生结构化事件。

## 路线图

| 阶段 | 状态 | 内容 |
| --- | --- | --- |
| 系统设计 M1 | ✅ 完成 | `helm-core`、`helm-agent-engine`、`helm-runtime`、`FakeProvider`、`InMemoryRuntimeStore` |
| 系统设计 M2 | ✅ 完成 | OpenAI / Anthropic provider、Skill loading、In-memory sandbox、Local sandbox |
| 系统设计 M3 | ✅ 完成 | HTTP routes、`helm dev`、`helm run`、run inspection |
| 系统设计 M4 | ✅ 完成 | Spring Boot starter、自动配置、bean discovery、示例项目 |
| 系统设计 M5 | ✅ 完成 | JDBC store、schema migrations、event persistence、logging observer |
| 缺口补齐 | ✅ 完成 | Memory 管理、Session 管理生命周期、历史裁剪（2026-07-05） |
| 生产化 M0–M11 | 🟡 设计中 | 流式 API、engine hardening、JsonSchema 扩展、memory 语义检索、authorizer、client SDK、rate limiting、metrics/OTel、durable scale、release engineering、API governance —— 11 篇组件方案见 [`docs/design/`](docs/design/README.md) |

长期计划与进度跟踪见 [`docs/roadmap.md`](docs/roadmap.md)。

## 文档

- [Helm MVP 设计文档](docs/helm-mvp-design.md)
- [组件设计方案](docs/design/README.md)：11 个待实现生产能力的逐组件设计
- [生产路线图](docs/roadmap.md)

## 示例

- [Coding Workflow](examples/coding-workflow/)：从 GitHub issue 读取需求，设计方案，审查设计，开发代码，执行验证，代码审查并创建 pull request 的软件开发自动化 workflow。

## 状态

Helm 目前还不是可用发布版本。当前仓库用于沉淀架构设计、API 形态和实现计划。

## License

License 尚未确定。
