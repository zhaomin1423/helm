# Helm MVP 设计文档

Helm 是一个面向 Java 生态的 Agent Harness Framework。它的核心思想是：模型不是一个孤立的聊天 API，而是运行在一个可编程 harness 中，通过 session、tool、skill、sandbox、workflow、事件流和持久化完成真实工作。

本文档定义 Helm 的 MVP 设计。已确认的路线是 **Core-first**：先实现不绑定 Web 框架的 Java Runtime Core，再在外层提供 Spring Boot、HTTP、CLI、Provider、Sandbox、Persistence 等集成。

## 1. 目标

Helm MVP 需要支持：

1. 使用 Java 定义 Agent、Workflow、Tool、Skill 和 Sandbox。
2. 通过 Java API 与 HTTP API 运行持久 Agent session。
3. 运行有明确输入、输出、状态和事件历史的有限 Workflow。
4. 通过稳定 SPI 接入可替换的模型 Provider。
5. 通过 Sandbox SPI 为 Agent 提供受控文件系统和 shell 环境。
6. 持久化 session、operation、workflow run 和 runtime event。
7. 集成 Spring Boot，但不让 Spring 成为 runtime core 的依赖。
8. 提供 CLI，用于本地开发、运行 workflow 和检查运行记录。

MVP 不包含：

1. 分布式调度。
2. 多租户权限体系。
3. 插件市场。
4. 生产级容器隔离。
5. 完整第一方 channel 生态。
6. 跨进程 exactly-once durable execution。

## 2. 设计原则

| 原则 | 说明 |
| --- | --- |
| Core-first | Runtime core 不依赖 Spring、Servlet、Netty 或任何具体应用框架。 |
| Harness-first | Agent 是运行在 harness 中的模型，不是聊天 API 包装器。 |
| 明确能力边界 | Tool、Skill、Provider、Filesystem、Shell 都必须显式注册。 |
| 可替换基础设施 | Provider、Sandbox、Persistence、Transport、Observer 都通过 SPI 接入。 |
| 安全默认值 | 默认禁用 shell，HTTP 暴露必须显式开启，tool 输入需要校验。 |
| 可观测执行 | Run、Operation、Tool call、Provider call、错误都产生结构化事件。 |

## 3. 项目结构

建议使用 Gradle 或 Maven 多模块结构：

```text
helm/
  helm-core/
  helm-agent-engine/
  helm-runtime/
  helm-http-core/
  helm-http-servlet/
  helm-cli/
  helm-spring-boot-starter/
  helm-provider-openai/
  helm-provider-anthropic/
  helm-sandbox-local/
  helm-persistence-jdbc/
  helm-observability-logging/
  examples/
```

| 模块 | 职责 |
| --- | --- |
| `helm-core` | 领域模型、公开接口、配置模型、结构化错误。 |
| `helm-agent-engine` | Agent loop、turn 执行、模型流规范化、tool-call 编排、上下文溢出处理和 compaction。 |
| `helm-runtime` | Agent runtime、workflow runtime、session、operation、harness 生命周期、持久化和事件总线。 |
| `helm-http-core` | 与框架无关的 request/response DTO、route spec、handler abstraction。 |
| `helm-http-servlet` | Servlet adapter，把 `helm-http-core` 挂到 Servlet 容器。 |
| `helm-cli` | 本地开发服务器、workflow 调用、运行记录检查命令。 |
| `helm-spring-boot-starter` | Spring Boot 自动配置和组件发现。 |
| `helm-provider-openai` | OpenAI 兼容模型 provider。 |
| `helm-provider-anthropic` | Anthropic 模型 provider。 |
| `helm-sandbox-local` | 内存 sandbox、本地文件系统和 shell sandbox。 |
| `helm-persistence-jdbc` | JDBC runtime store 和数据库迁移。 |
| `helm-observability-logging` | Runtime event 的日志 observer。 |

## 4. 核心概念

| 概念 | Java 类型 | 说明 |
| --- | --- | --- |
| Agent definition | `AgentDefinition` | 可复用的 Agent 配置工厂。 |
| Agent instance | `AgentInstanceId` | 可寻址运行时身份，由 agent name 和 instance id 组成。 |
| Harness | `AgentHarness` | Agent 运行环境，包含 session、tool、skill、sandbox 和 provider 配置。 |
| Session | `AgentSession` | 持久对话和 operation 上下文。 |
| Operation | `Operation` | 一次 prompt、skill、tool、task 或 compaction 调用。 |
| Turn | `Turn` | Operation 内部的一次模型 round trip，包含 tool call 和 tool result。 |
| Agent loop | `AgentLoop` | 内部引擎，重复执行模型调用和 tool 调用，直到得到终止响应。 |
| Workflow definition | `WorkflowDefinition<I, O>` | 有限任务定义，带类型化输入和输出。 |
| Workflow run | `WorkflowRun` | Workflow 的一次调用记录，包含状态、结果、事件和错误。 |
| Tool | `Tool<I, O>` | 模型可调用的类型化应用能力。 |
| Skill | `SkillDefinition` | 可复用的指令和文件包。 |
| Sandbox | `Sandbox` | 受控文件系统和 shell 边界。 |
| Provider | `ModelProvider` | LLM provider 适配器。 |
| Runtime store | `RuntimeStore` | session、operation、run、event 的持久化接口。 |

## 5. 公开 Java API

### 5.1 Agent Definition

```java
public interface AgentDefinition {
    AgentConfig configure(AgentContext context);
}
```

示例：

```java
public final class SupportAgent implements AgentDefinition {
    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
            .model("openai/gpt-4.1")
            .instructions("You are a support triage agent.")
            .tool(new LookupTicketTool())
            .skill(SkillDefinition.fromClasspath("skills/support/SKILL.md"))
            .sandbox(Sandboxes.inMemory())
            .build();
    }
}
```

Agent definition 是无状态配置工厂。运行状态属于 agent instance 和 session。

### 5.2 Workflow Definition

```java
public interface WorkflowDefinition<I, O> {
    WorkflowConfig config();
    TypeDescriptor<I> inputType();
    TypeDescriptor<O> outputType();
    O run(WorkflowContext<I> context) throws Exception;
}
```

示例：

```java
public final class SummarizeWorkflow
    implements WorkflowDefinition<SummarizeInput, SummarizeOutput> {

    @Override
    public WorkflowConfig config() {
        return WorkflowConfig.builder()
            .agent(new SummarizerAgent())
            .build();
    }

    @Override
    public TypeDescriptor<SummarizeInput> inputType() {
        return TypeDescriptor.of(SummarizeInput.class);
    }

    @Override
    public TypeDescriptor<SummarizeOutput> outputType() {
        return TypeDescriptor.of(SummarizeOutput.class);
    }

    @Override
    public SummarizeOutput run(WorkflowContext<SummarizeInput> context) throws Exception {
        AgentSession session = context.harness().session("default");
        PromptResult result = session.prompt(context.input().text());
        return new SummarizeOutput(result.text());
    }
}
```

Workflow 是有限任务，会生成 run record。需要跨消息或跨事件持续工作的场景，应直接使用 Agent。

### 5.3 Tool Definition

```java
public interface Tool<I, O> {
    String name();
    TypeDescriptor<I> inputType();
    TypeDescriptor<O> outputType();
    JsonSchema inputSchema();
    O execute(ToolContext context, I input) throws Exception;
}
```

`TypeDescriptor` 必须保留 Java 泛型运行时信息，不能只依赖 `Class<T>`。HTTP 层用 workflow 的 `inputType()` / `outputType()` 做 JSON 反序列化和输出校验；agent engine 用 tool 的 `inputSchema()` 暴露模型可调用 schema。简单 record 可以用 `TypeDescriptor.of(MyRecord.class)`，`List<Foo>`、`Map<String, Bar>` 等泛型输入必须使用保留泛型的 descriptor。

Tool 是模型推理与应用副作用之间的主要安全边界。一个 tool 应该暴露一个窄而明确的能力，而不是暴露一个宽泛客户端。

### 5.4 Model Provider SPI

```java
public interface ModelProvider {
    boolean supports(ModelRef model);
    Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) throws HelmException;
}
```

`ModelRef` 是结构化模型标识，至少包含 `providerId` 和 `modelId`，例如 `openai/gpt-4.1` 会解析为 `providerId=openai`、`modelId=gpt-4.1`。Provider registry 不能只用自由字符串匹配模型，否则多个 OpenAI-compatible provider 会产生歧义。

MVP 采用 streaming-first SPI。即使调用方需要阻塞结果，也由 `helm-agent-engine` 消费 `ModelStreamEvent` 并聚合成最终 `ModelResponse`。这样 `ModelStreamNormalizer`、partial content、tool-call delta、usage 和 provider error 都有统一输入来源，后续不需要破坏 provider SPI。

### 5.5 Sandbox SPI

```java
public interface Sandbox {
    SandboxFileSystem fs();
    SandboxShell shell();
}
```

MVP 提供两个实现：

1. `InMemorySandbox`：用于测试和安全本地默认值。
2. `LocalSandbox`：用于开发环境，绑定显式工作目录。

Shell 默认禁用，必须通过配置显式开启。

## 6. Runtime 架构

```text
Application code
  -> AgentRuntime / WorkflowRuntime
    -> HarnessFactory
      -> ProviderRegistry
      -> ToolRegistry
      -> SkillRegistry
      -> SandboxFactory
    -> OperationRunner
      -> AgentEngine
        -> TurnRunner
        -> ModelStreamNormalizer
        -> ToolCallOrchestrator
        -> ContextManager
    -> RuntimeStore
    -> EventBus
```

### 6.1 Agent Runtime

`AgentRuntime` 负责解析 agent definition、初始化 harness、打开或创建 session、运行 operation、保存结果并发出事件。

主要方法：

```java
PromptResult prompt(AgentPromptRequest request);
OperationHandle dispatch(AgentDispatchRequest request);
AgentHarness init(AgentDefinition definition, String instanceId);
```

`prompt` 是 request/response 用户交互：调用方等待 agent operation 完成，并获得 assistant text 或结构化结果。`dispatch` 是外部事件投递：调用方向持久 agent instance 提交一个事件，返回 `OperationHandle`，不保证立即返回 assistant text。MVP 可以同步处理 dispatch 的内部执行，但 HTTP 和 Java API 必须表现为 operation admission，避免未来异步化时破坏兼容性。

同一 session 内 MVP 保证最多只有一个 active operation。Runtime 可以选择 per-session queue，也可以在有 active operation 时返回 `SESSION_BUSY`；无论哪种策略，session state 的读取、operation append、message append 和 version 更新必须在同一个一致性边界内完成。

### 6.2 Workflow Runtime

`WorkflowRuntime` 创建 workflow run，并执行有限 workflow definition。

主要方法：

```java
WorkflowRunHandle invoke(WorkflowInvokeRequest request);
WorkflowRun getRun(String runId);
List<RuntimeEvent> getRunEvents(String runId);
```

Workflow 生命周期：

```text
PENDING -> RUNNING -> SUCCEEDED
                  -> FAILED
```

`CANCELLED`、cancel endpoint 和跨 provider/tool 的统一取消传播不属于 MVP。MVP 的 timeout 先通过 operation deadline、provider request timeout、tool timeout 和 sandbox command timeout 体现；后续加入取消能力时再扩展 workflow 状态机。

### 6.3 Session Runtime

`AgentSession` 持有持久对话上下文和 operation 历史。

```java
AgentSession session = harness.session("default");
PromptResult result = session.prompt("Review this issue.");
SkillResult skill = session.skill(reviewSkill);
```

Session 身份：

```text
agentName + instanceId + sessionName
```

Session store 必须维护 session version。持久化实现使用 optimistic lock 防止两个 operation 基于同一个旧上下文提交结果；冲突时 runtime 返回 `SESSION_BUSY` 或重试队列中的下一个 operation。

### 6.4 Agent Engine

`helm-agent-engine` 是 Helm 内部的 Agent/Model 内核层，负责承接模型调用、agent loop、消息归一化、tool-call 编排和上下文管理等底层职责。

Engine 使用 Helm 自己的稳定 Java 类型定义运行边界，避免公开 API 被任何外部实现或特定 provider 的消息模型、streaming 语义锁定。

| 内核职责 | Helm 组件 |
| --- | --- |
| Agent loop 和状态 | `AgentLoop` |
| 一次模型 round trip | `TurnRunner` |
| Provider streaming 协议 | `ModelStreamEvent`、`ModelStreamNormalizer` |
| 消息和 content 模型 | `HelmMessage`、`ContentBlock` |
| Tool call 和 tool result | `ToolCall`、`ToolResult`、`ToolCallOrchestrator` |
| Usage 统计 | `TokenUsage` |
| Context overflow 归一化 | `ContextOverflowException` |
| Compaction 决策和摘要 | `ContextManager` |

这个 engine 应该保持小而确定：接收准备好的 `AgentEngineRequest`，调用 `ModelProvider`，通过窄接口 `ToolExecutor` 执行 tool，追加消息，发出 runtime event，并重复执行，直到模型产生终止 assistant response 或 operation 达到停止条件。

## 7. 数据流

### 7.1 Agent Prompt Flow

```text
HTTP 或 Java caller
  -> AgentRuntime.prompt
  -> resolve AgentDefinition
  -> create AgentHarness
  -> open AgentSession
  -> create Operation
  -> AgentEngine.run
  -> TurnRunner builds ModelRequest
  -> ModelProvider streams ModelStreamEvent values
  -> ToolCallOrchestrator executes requested tools
  -> repeat model/tool turns until terminal response
  -> store messages and operation result
  -> return PromptResult
```

### 7.2 Workflow Flow

```text
HTTP、CLI 或 Java caller
  -> WorkflowRuntime.invoke
  -> create WorkflowRun
  -> initialize workflow harness
  -> run workflow handler
  -> use session/tool/skill/sandbox as needed
  -> validate output
  -> store result or structured error
  -> return run id and optional result
```

## 8. 持久化

MVP 提供：

1. `InMemoryRuntimeStore`：用于测试和本地示例。
2. `JdbcRuntimeStore`：用于本地或服务端持久化。

建议表结构：

```sql
agent_sessions(
  id varchar primary key,
  agent_name varchar not null,
  instance_id varchar not null,
  session_name varchar not null,
  version bigint not null,
  state_json text not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

operations(
  id varchar primary key,
  session_id varchar,
  type varchar not null,
  status varchar not null,
  input_json text,
  output_json text,
  error_json text,
  created_at timestamp not null,
  completed_at timestamp
);

workflow_runs(
  id varchar primary key,
  workflow_name varchar not null,
  status varchar not null,
  input_json text,
  output_json text,
  error_json text,
  created_at timestamp not null,
  completed_at timestamp
);

runtime_events(
  id varchar primary key,
  session_id varchar,
  operation_id varchar,
  workflow_run_id varchar,
  turn_id varchar,
  sequence bigint not null,
  type varchar not null,
  payload_json text not null,
  created_at timestamp not null
);
```

MVP 持久化保证：

1. 可通过 agent name、instance id、session name 恢复 session。
2. 可通过 run id 查询 workflow run。
3. 可通过 operation id 查询 agent operation。
4. 错误以结构化数据保存，不只保存 message 字符串。
5. Runtime event 通过 `sequence` 保证同一 operation 或 workflow run 内的读取顺序。

MVP 采用 snapshot-first session 持久化：`agent_sessions.state_json` 保存可恢复的消息历史、compaction summary 和 session metadata。`runtime_events` 保存可观测历史，不作为恢复的唯一来源。Compaction 后的 session context 可以被压缩，但 runtime event 仍保留已脱敏的历史事件，用于调试和审计。

## 9. HTTP API

HTTP route 由 `helm-http-core` 定义，并由 `helm-http-servlet` 或 Spring Boot starter 挂载。`helm-http-core` 只包含 DTO、route spec 和 `HelmHttpHandler` 抽象，不依赖 Servlet、Netty 或 Spring。

### 9.0 HTTP Security Model

MVP 不内置认证系统。生产部署必须由宿主应用的 middleware、filter 或网关完成认证与授权，再把 principal、tenant、request id 等信息传入 `HelmSecurityContext`。

默认规则：

1. HTTP 暴露必须显式开启。
2. `helm dev` 默认只监听 `127.0.0.1`。
3. Agent instance id 不代表授权，宿主应用必须判断调用者是否能访问该 instance。
4. Helm HTTP adapter 接受可选 `HelmAuthorizer`。如果配置了 authorizer，每个 prompt、dispatch、workflow invoke、inspection 请求都必须先授权。

```java
public interface HelmAuthorizer {
    void authorize(HelmSecurityContext context, HelmAction action) throws HelmException;
}
```

### 9.1 Agent Prompt

```http
POST /agents/{agentName}/{instanceId}/prompt
Content-Type: application/json

{
  "session": "default",
  "input": {
    "text": "Help me understand this ticket."
  }
}
```

响应：

```json
{
  "operationId": "op_123",
  "text": "The ticket is about a failed payment."
}
```

### 9.2 Agent Dispatch

```http
POST /agents/{agentName}/{instanceId}/dispatch
Content-Type: application/json

{
  "session": "default",
  "input": {
    "type": "ticket.comment",
    "text": "Customer added a new comment."
  }
}
```

响应：

```json
{
  "operationId": "op_123",
  "status": "RUNNING"
}
```

### 9.3 Workflow Invoke

```http
POST /workflows/{workflowName}?wait=result
Content-Type: application/json

{
  "text": "Long document..."
}
```

响应：

```json
{
  "runId": "run_123",
  "result": {
    "summary": "..."
  }
}
```

### 9.4 Inspection

```http
GET /operations/{operationId}
GET /operations/{operationId}/events
GET /workflow-runs/{runId}
GET /workflow-runs/{runId}/events
```

Agent operation 和 workflow run 是不同概念。Agent prompt / dispatch 产生 `operationId`；workflow invoke 产生 `runId`。两者都可以检查事件，但不共享命名空间。

## 10. CLI

`helm-cli` 提供本地开发命令：

```bash
helm dev
helm run summarize --input '{"text":"hello"}'
helm agents
helm workflows
helm inspect operation op_123
helm inspect workflow-run run_123
```

CLI 职责：

1. 启动本地 HTTP runtime。
2. 从命令行调用 workflow。
3. 列出已发现的 agent 和 workflow。
4. 查看 agent operation、workflow run 和 event 历史。
5. 在开发环境加载 `.env` 和本地配置。

## 11. Spring Boot 集成

Spring Boot 支持位于 `helm-spring-boot-starter`。

能力：

1. 自动配置 `AgentRuntime`、`WorkflowRuntime`、`RuntimeStore`、`ProviderRegistry` 和 observer。
2. 发现 `AgentDefinition`、`WorkflowDefinition`、`Tool`、`ModelProvider` bean。
3. 注册 Servlet 或 WebFlux adapter，把 `helm-http-core` 的 handler 挂到宿主应用。
4. 从 `application.yml` 绑定配置。

Spring 场景下，`AgentDefinition` 可以由 DI 容器创建，依赖数据库、HTTP client、metrics 的 tool 也应作为 bean 注入或通过 `ToolRegistry` 引用。文档中的 `new LookupTicketTool()` 只表示 pure Java 最小示例，不要求 Spring 应用手动 new 带资源依赖的 tool。

示例配置：

```yaml
helm:
  http:
    enabled: true
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
  sandbox:
    local:
      shell-enabled: false
  persistence:
    type: jdbc
```

## 12. Skills

Skill 是可复用的指令包。

示例结构：

```text
skills/
  review/
    SKILL.md
    CHECKLIST.md
    examples/
```

API：

```java
SkillDefinition review = SkillDefinition.fromClasspath("skills/review/SKILL.md");
```

规则：

1. Skill 必须先在 agent config 中显式注册，才能被使用。
2. Skill 文件访问限制在 skill 目录内。
3. 只有被引用时，skill 内容才进入 operation context。
4. Skill 内容可以打包进应用资源，也可以从配置目录加载。

## 13. Sandbox

MVP sandbox：

| Sandbox | 用途 |
| --- | --- |
| `InMemorySandbox` | 测试、示例和确定性 workflow 的安全默认值。 |
| `LocalSandbox` | 绑定配置工作目录的开发 sandbox。 |

`LocalSandbox` 是开发便利，不是抵御恶意代码的安全边界。它只提供 Helm 层的路径规范化、workspace 限制和命令策略；一旦启用本地 shell，进程仍可能访问网络、fork 子进程、读取可见环境或消耗本机资源。生产隔离必须使用外部容器、VM 或远程 sandbox adapter。

Local sandbox 限制：

1. 所有文件路径都限制在配置 workspace 下。
2. 拒绝路径穿越。
3. 默认禁用 shell。
4. 命令必须有超时。
5. 限制输出大小。
6. 只传递 allowlist 环境变量。
7. Shell 命令必须经过 command allowlist 或 policy 检查。
8. 固定 working directory，禁止命令自行选择宿主机路径作为执行目录。

## 14. 事件与可观测性

Runtime 产生结构化事件：

```java
public sealed interface RuntimeEvent permits
    RunStarted,
    RunCompleted,
    OperationStarted,
    OperationCompleted,
    ModelRequested,
    ModelResponded,
    ToolStarted,
    ToolCompleted,
    SkillStarted,
    SkillCompleted,
    SandboxCommandStarted,
    SandboxCommandCompleted,
    ErrorOccurred {
}
```

MVP observer：

1. Logging observer。
2. In-memory test observer。

事件默认必须经过 redaction。Provider headers、API keys、环境变量、developerDetails 和已标记 secret 的 tool 参数不进入 event payload。Prompt、model output、tool input/output 是否完整记录由 observer 配置控制；logging observer 默认记录 metadata 和摘要，不记录完整敏感内容。

未来可扩展：

1. OpenTelemetry。
2. Sentry。
3. 类 Braintrust 的 eval tracing。
4. Prometheus metrics。

## 15. 错误处理

框架错误必须使用结构化异常。

```java
public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;
}
```

核心错误：

| 错误 | 含义 |
| --- | --- |
| `AgentNotFoundException` | 请求的 agent 未注册。 |
| `WorkflowNotFoundException` | 请求的 workflow 未注册。 |
| `ProviderNotFoundException` | 没有 provider 支持配置的模型。 |
| `ToolExecutionException` | Tool 执行失败或返回非法输出。 |
| `SandboxException` | 文件系统或 shell 操作失败。 |
| `ValidationException` | 输入或输出校验失败。 |
| `PersistenceException` | Runtime store 操作失败。 |

HTTP 响应应包含稳定错误码和安全 details。默认不向外部调用者返回本地路径、堆栈、配置内部信息等 developer-only 信息。

## 16. 安全

MVP 安全要求：

1. HTTP route 暴露必须显式开启。
2. Agent instance id 由应用拥有，不代表授权。
3. 模型 provider 凭据只在服务端读取。
4. Tool 暴露窄能力，并校验类型化输入。
5. Skill 不能读取注册目录以外的文件。
6. Local shell 默认禁用。
7. Sandbox 文件系统访问必须 root 和 normalize。
8. Runtime event 必须先经过 redactor，再进入 store 或 observer。
9. 请求体大小必须限制。
10. Provider 和 tool 错误不能泄露凭据。
11. HTTP adapter 必须接收宿主应用传入的 `HelmSecurityContext`，不能从 agent instance id 推断身份。

## 17. 测试策略

### Unit Tests

覆盖：

1. Agent config 初始化。
2. Workflow 状态转换。
3. Session 创建和恢复。
4. Tool schema 生成。
5. Tool 执行和错误包装。
6. Agent loop 终止、重试和 tool-call 顺序。
7. Model stream 规范化。
8. Context overflow 分类。
9. Session busy / optimistic lock 行为。
10. Provider registry 选择和 `ModelRef` 解析。
11. In-memory sandbox 文件系统行为。
12. 结构化错误序列化。
13. Event redaction。

### Integration Tests

覆盖：

1. 使用 fake provider 的 HTTP agent prompt。
2. 使用 fake provider 的 HTTP workflow invoke。
3. Agent operation inspection。
4. Workflow run inspection。
5. JDBC runtime store round trip。
6. Spring Boot 自动配置。
7. CLI workflow 执行。

### Contract Tests

覆盖：

1. `ModelProvider` SPI 行为。
2. `Sandbox` SPI 行为。
3. `RuntimeStore` SPI 行为。
4. `HelmAuthorizer` 集成行为。
5. HTTP error response 结构。

## 18. MVP 里程碑

### Milestone 1：Core Runtime

交付：

1. `helm-core`
2. `helm-agent-engine`
3. `helm-runtime`
4. `AgentDefinition`
5. `WorkflowDefinition`
6. `AgentLoop`
7. `TurnRunner`
8. `AgentHarness`
9. `AgentSession`
10. `Tool`
11. `FakeProvider`
12. `InMemoryRuntimeStore`

### Milestone 2：Provider、Skill、Sandbox

交付：

1. OpenAI provider。
2. Anthropic provider。
3. 从 classpath 加载 skill。
4. In-memory sandbox。
5. Local sandbox。

### Milestone 3：HTTP 和 CLI

交付：

1. Agent prompt route。
2. Agent dispatch route。
3. Workflow invoke route。
4. Operation inspection routes。
5. Workflow run inspection routes。
6. `helm dev`。
7. `helm run`。

### Milestone 4：Spring Boot Starter

交付：

1. 自动配置。
2. Bean discovery。
3. `application.yml` properties。
4. Spring Boot 示例。

### Milestone 5：JDBC 和 Observability

交付：

1. JDBC runtime store。
2. Schema migrations。
3. Runtime event 持久化。
4. Logging observer。

## 19. 推荐技术选型

| 领域 | 推荐 |
| --- | --- |
| Java | Java 21 |
| 构建 | Gradle Kotlin DSL 或 Maven |
| JSON | Jackson |
| CLI | Picocli |
| HTTP | `helm-http-core` 定义 handler 抽象；MVP 优先提供 Servlet adapter |
| 持久化 | JDBC + Flyway |
| 测试 | JUnit 5 + AssertJ |
| Mock HTTP | WireMock |
| 日志 | SLF4J |
| 可观测性 | 后续模块接入 OpenTelemetry API |

## 20. 示例使用方式

定义 Agent：

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

定义 Workflow：

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

本地运行：

```bash
helm run review --input '{"content":"..."}'
```

启动本地服务：

```bash
helm dev
```

## 21. 命名

项目名为 **Helm**。

这个名字表示自治 Agent 的控制面：Helm 不替代模型，而是给模型方向、边界、工具、记忆和安全执行环境。

为了避免和 Kubernetes Helm 在包名或搜索结果中混淆，公开 Java 坐标应使用明确的 owner namespace，例如：

```text
io.github.zhaomin.helm
```

也可以换成实际发布方的命名空间。

## 22. 结论

Helm MVP 应该是一个小而完整的 Java Agent Harness Framework。第一版应优先保证清晰的 runtime 边界，而不是追求功能数量：

1. Runtime core 独立于 Web 框架。
2. 使用第一方 `helm-agent-engine` 承担 agent loop、turn、model stream 和 tool-call 编排。
3. Agent session 用于持久工作。
4. Workflow run 用于有限任务。
5. Typed tool 是受控能力边界。
6. Skill 是可复用知识包。
7. Sandbox 是执行边界。
8. Provider、Persistence、HTTP、CLI、Spring Boot 都是可替换集成。

这样 Helm 能保持清晰的 harness-first 架构，同时自然适配 Java 生态。
