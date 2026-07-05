# 02 — Engine Hardening

> 组件编号：#2 · 来源 milestone：M3 · 状态：proposed · 关联组件：#1 streaming、#3 json schema、#8 metrics/otel、#11 api governance

## 1. 背景与目标

### 1.1 为什么需要

`helm-agent-engine` 是 Helm 的执行核心，承担 turn 循环、model stream 聚合、tool-call 调度三件事。当前实现（见 `AgentEngine.java` / `TurnRunner.java`）只覆盖 happy path：

- 循环到 `maxTurns` 用尽即抛裸 `IllegalStateException`。
- model stream 超时/中断/失败一律包成裸 `IllegalStateException`。
- 整个 turn 循环没有任何 engine 事件回调，外部观察者无法知道"现在跑到第几 turn、调了哪个 tool、用了多少 token"。
- `ModelStreamEvent.Completed(usage)` 里的 `TokenUsage` 被 `TurnRunner` 直接丢弃。
- `ToolExecutor.executeUnchecked` 之前没有 schema 校验，tool 返回值之后也没有 sanity check。
- `ContextOverflowException`（`code=CONTEXT_OVERFLOW`）已经在 `helm-core` 定义但从未被 engine 抛出。

### 1.2 目标

本组件目标是把 `helm-agent-engine` 从"能跑通 happy path"提升到"控制流结构化、可观测、可校验、可替换"，对齐 `docs/roadmap.md` M3 验收：

1. engine control flow 不再泄漏裸 `IllegalStateException`。
2. invalid tool input 在用户 tool code 之前失败。
3. invalid tool output 映射为 `ToolExecutionException`。
4. token usage 出现在 `AgentEngineResult` 和事件 payload 中。

### 1.3 不解决什么

- 不暴露 streaming API（增量 token 转发到调用方），见 [`01-streaming-api.md`](01-streaming-api.md)。
- 不扩展 `JsonSchema` 类型系统（Map/enum/optional），见 [`03-json-schema-extensions.md`](03-json-schema-extensions.md)；本设计假设 schema 已能描述 tool input。
- 不引入 async/queue/lease/journal，见 [`09-durable-scale-runtime.md`](09-durable-scale-runtime.md)。
- 不替换 `Flow.Publisher` 流式模型。
- 不实现真实 token 计数（只定义 SPI 钩子，留待 provider adapter 实现）。

## 2. 现状与缺口

### 2.1 AgentEngine

`helm-agent-engine/src/main/java/io/agent/helm/engine/AgentEngine.java:12-39`

```java
public final class AgentEngine {
    private final TurnRunner turnRunner = new TurnRunner();

    public AgentEngineResult run(AgentEngineRequest request) {
        List<HelmMessage> messages = new ArrayList<>(request.messages());
        for (int turn = 0; turn < request.maxTurns(); turn++) {
            TurnResult result = turnRunner.run(...);
            if (result.toolCalls().isEmpty()) {
                HelmMessage assistant = HelmMessage.assistant(result.text());
                messages.add(assistant);
                return new AgentEngineResult(result.text(), messages);
            }
            for (ModelStreamEvent.ToolCallRequested toolCall : result.toolCalls()) {
                messages.add(...);
                Object output = request.toolExecutor().execute("engine", toolCall.name(), toolCall.input());
                messages.add(...);
            }
        }
        throw new IllegalStateException("Agent loop exceeded max turns");  // 行 37
    }
}
```

缺口：

- **行 17-35**：turn 循环无任何 engine 事件回调（turn/model/tool start/end/fail）。
- **行 32**：`request.toolExecutor().execute(...)` 前无 input schema 校验，后无 output sanity check；`operationId` 写死字符串 `"engine"` 而非真实 operation id。
- **行 37**：超 `maxTurns` 抛裸 `IllegalStateException`，违反"engine control flow 不泄漏裸 `IllegalStateException`"。
- **行 26**：`AgentEngineResult(text, messages)` 没有累计 `TokenUsage`。

### 2.2 TurnRunner

`helm-agent-engine/src/main/java/io/agent/helm/engine/TurnRunner.java:14-74`

```java
final class TurnRunner {
    TurnResult run(ModelProvider provider, ModelRequest request) {
        // ... Flow.Subscriber 收集 text + toolCalls
        try {
            if (!done.await(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                cancel(subscriptionRef);
                throw new IllegalStateException("Model stream timed out");  // 行 57
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(subscriptionRef);
            throw new IllegalStateException("Interrupted while waiting for model stream", e);  // 行 62
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Model stream failed", throwable);  // 行 70
        }
        return new TurnResult(text.toString(), List.copyOf(toolCalls));
    }
}
```

缺口：

- **行 29-40**：`onNext` 处理了 `ContentDelta` 和 `ToolCallRequested`，但忽略 `ModelStreamEvent.Completed`——`usage` 被丢弃。
- **行 57 / 62 / 70**：超时、中断、stream 失败三处都抛裸 `IllegalStateException`。
- **行 21-52**：stream 失败时不区分 provider 错误（含 context overflow）与一般 stream 异常。
- **行 73**：`TurnResult(text, toolCalls)` 不携带 `TokenUsage`。

### 2.3 错误模型现状

`helm-core/src/main/java/io/agent/helm/core/error/`：

- `HelmException(code, message, details, developerDetails)`：抽象基类，code 是稳定 `SCREAMING_SNAKE_CASE`。
- `ContextOverflowException`：硬编码 `code=CONTEXT_OVERFLOW`，但 engine 从不抛出。无子类型区分（输入超限 vs 单轮输出超限 vs 历史累积超限）。
- `ToolExecutionException`：硬编码 `code=TOOL_EXECUTION_FAILED`。`AgentRuntime.executeTool`（`AgentRuntime.java:306-328`）捕获 `RuntimeException` / `Exception` 都包成同一个 code，无法区分 input/output invalid 与 tool 自身抛错。

### 2.4 事件类型现状

`helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventType.java:3-26` 已经预定义：

```
TURN_STARTED / TURN_SUCCEEDED / TURN_FAILED
MODEL_STARTED / MODEL_SUCCEEDED / MODEL_FAILED
TOOL_STARTED / TOOL_SUCCEEDED / TOOL_FAILED
SKILL_* / SANDBOX_* / ERROR_RECORDED
```

但 `AgentRuntime.executePrompt`（`AgentRuntime.java:127-240`）只发 `OPERATION_STARTED` / `OPERATION_SUCCEEDED` / `OPERATION_FAILED` 三种事件——`TURN_*` / `MODEL_*` / `TOOL_*` 全部空置。

### 2.5 Roadmap 出处

- `docs/roadmap.md` 第 4 节 M3 行：`proposed`，P0。
- `docs/roadmap.md` M3 交付项：engine event callback、turn/model/tool events、tool input/output validation、token usage aggregation、structured engine errors、context overflow 分类。
- `docs/roadmap.md` M3 验收：engine control flow 不泄漏裸 `IllegalStateException`；invalid tool input 在用户 tool code 前失败；invalid tool output 映射为 `ToolExecutionException`；usage 出现在 result 和 event 中。
- `docs/design/README.md` 第 2 节缺口行 #2 引用本文件。

## 3. 设计方案

### 3.1 模块归属

| 能力 | 落点 | 理由 |
| --- | --- | --- |
| `EngineEvent` 数据类型 + `EngineEventListener` SPI | `helm-agent-engine` | engine 专属事件，不应污染 `helm-core`；core 已有通用 `RuntimeEventObserver` 处理 runtime 级事件 |
| `RuntimeEventType` 中的 `TURN_*` / `MODEL_*` / `TOOL_*` | `helm-core`（已存在） | runtime event taxonomy 是横切的，已预定义 |
| `EngineException` 层级 | `helm-core/error` | 错误类型属于稳定 SPI，HTTP/CLI/SDK 都需要按 code 映射响应 |
| `ContextOverflowException` 子分类 | `helm-core/error` | 同上 |
| `ToolExecutionException` code 扩展 | `helm-core/error` | 同上 |
| `JsonSchemaValidator` SPI | `helm-core/type` | 校验是契约能力，`AgentRuntime` 与未来 OTel adapter 都可能复用 |
| `TokenCounter` SPI（可选） | `helm-core/model` | 用于 ACCUMULATED_OVERFLOW 预估，provider adapter 实现 |
| `ToolInputValidator` / `ToolOutputValidator` 内部类 | `helm-agent-engine`（package-private） | 实现细节，不外露 |
| `TokenUsage` 累加方法 | `helm-core/model/TokenUsage` | 不可变 record 加 `add` 静态方法 |

依赖守则验证：

- `helm-agent-engine` 仅依赖 `helm-core`，新增 `EngineEvent` / `EngineEventListener` 不引入新依赖。
- `helm-core` 不依赖 `helm-agent-engine`，`RuntimeEventType` 已是 core 子包。
- 错误类型扩展不引入新依赖。

### 3.2 Engine 事件 SPI

定义在 `helm-agent-engine/src/main/java/io/agent/helm/engine/event/`：

```java
package io.agent.helm.engine.event;

import io.agent.helm.core.model.TokenUsage;
import java.time.Duration;
import java.time.Instant;

/**
 * 引擎执行过程中产生的结构化事件。引擎本身只发 EngineEvent；
 * 由 AgentRuntime 提供的 EngineEventListener 负责转换为 RuntimeEventRecord
 * 并路由到 RuntimeStore + RuntimeEventObserver。
 *
 * 事件序列保证：start → (ended | failed)，turn 之间严格有序，
 * tool 事件嵌套在 turn 事件之内。
 */
public sealed interface EngineEvent permits
        EngineEvent.TurnStarted, EngineEvent.TurnEnded, EngineEvent.TurnFailed,
        EngineEvent.ModelStarted, EngineEvent.ModelEnded, EngineEvent.ModelFailed,
        EngineEvent.ToolStarted, EngineEvent.ToolEnded, EngineEvent.ToolFailed {

    /** 当前 turn 索引，从 0 开始。 */
    int turnIndex();

    /** 事件触发时间。 */
    Instant at();

    record TurnStarted(int turnIndex, Instant at) implements EngineEvent {}

    record TurnEnded(int turnIndex, Instant at, Duration duration, TokenUsage turnUsage) implements EngineEvent {}

    record TurnFailed(int turnIndex, Instant at, Duration duration, String errorCode, String message)
            implements EngineEvent {}

    record ModelStarted(int turnIndex, Instant at, String modelRef) implements EngineEvent {}

    record ModelEnded(int turnIndex, Instant at, Duration duration, TokenUsage modelUsage) implements EngineEvent {}

    record ModelFailed(int turnIndex, Instant at, Duration duration, String errorCode, String message)
            implements EngineEvent {}

    record ToolStarted(int turnIndex, Instant at, String toolName, String toolCallId) implements EngineEvent {}

    record ToolEnded(int turnIndex, Instant at, Duration duration, String toolName, String toolCallId)
            implements EngineEvent {}

    record ToolFailed(
            int turnIndex,
            Instant at,
            Duration duration,
            String toolName,
            String toolCallId,
            String errorCode,
            String message) implements EngineEvent {}
}
```

```java
package io.agent.helm.engine.event;

/**
 * 引擎事件监听器。AgentRuntime 提供实现，把 EngineEvent 转为
 * RuntimeEventRecord(type=RuntimeEventType.TURN_*/MODEL_*/TOOL_*, payload=...)
 * 后 append 到 RuntimeStore 并通知 RuntimeEventObserver。
 *
 * 监听器抛出的异常不会影响 engine 控制流（engine 内部 try/catch 包裹）。
 */
public interface EngineEventListener {
    void onEvent(EngineEvent event);

    static EngineEventListener noop() {
        return event -> {};
    }
}
```

**事件 → RuntimeEventRecord 映射**（在 `helm-runtime` 中实现，见 3.6）：

| EngineEvent | RuntimeEventType | payload 关键字段 |
| --- | --- | --- |
| TurnStarted | `TURN_STARTED` | `turnIndex` |
| TurnEnded | `TURN_SUCCEEDED` | `turnIndex`, `durationMs`, `inputTokens`, `outputTokens` |
| TurnFailed | `TURN_FAILED` | `turnIndex`, `durationMs`, `errorCode`, `message` |
| ModelStarted | `MODEL_STARTED` | `turnIndex`, `modelRef` |
| ModelEnded | `MODEL_SUCCEEDED` | `turnIndex`, `durationMs`, `usage` |
| ModelFailed | `MODEL_FAILED` | `turnIndex`, `durationMs`, `errorCode`, `message` |
| ToolStarted | `TOOL_STARTED` | `turnIndex`, `toolName`, `toolCallId` |
| ToolEnded | `TOOL_SUCCEEDED` | `turnIndex`, `toolName`, `toolCallId`, `durationMs` |
| ToolFailed | `TOOL_FAILED` | `turnIndex`, `toolName`, `toolCallId`, `errorCode`, `message` |

注：现有 `RuntimeEventType` 已经包含这些枚举值（`RuntimeEventType.java:10-18`），无需新增。

### 3.3 结构化错误

新增 `EngineException` 层级到 `helm-core/src/main/java/io/agent/helm/core/error/`：

```java
package io.agent.helm.core.error;

import java.util.Map;

/**
 * AgentEngine 控制流产生的错误。所有 engine 内部不再抛裸 IllegalStateException，
 * 一律走 EngineException 子类，保证 code 稳定可映射。
 */
public abstract class EngineException extends HelmException {
    protected EngineException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

/** 单轮 model stream 超时。code=TURN_TIMEOUT。 */
public final class TurnTimeoutException extends EngineException {
    public TurnTimeoutException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("TURN_TIMEOUT", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

/** agent loop 超过 maxTurns 仍未结束。code=MAX_TURNS_EXCEEDED。 */
public final class MaxTurnsExceededException extends EngineException {
    public MaxTurnsExceededException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("MAX_TURNS_EXCEEDED", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

/** model stream 失败但无法归类为 ContextOverflowException。code=MODEL_STREAM_FAILED。 */
public final class ModelStreamException extends EngineException {
    public ModelStreamException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("MODEL_STREAM_FAILED", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

/** 等待 model stream 时被中断。code=ENGINE_INTERRUPTED。 */
public final class EngineInterruptedException extends EngineException {
    public EngineInterruptedException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("ENGINE_INTERRUPTED", message, details, developerDetails);
    }
}
```

**Error code 注册表**（稳定 SCREAMING_SNAKE_CASE，对齐 `docs/contracts/11-api-governance.md` 待定规则）：

| code | exception | 触发场景 |
| --- | --- | --- |
| `TURN_TIMEOUT` | `TurnTimeoutException` | 单轮 model stream 超过 `ModelRequest.timeout()` |
| `MAX_TURNS_EXCEEDED` | `MaxTurnsExceededException` | turn 循环到 `maxTurns` 仍有 tool call |
| `MODEL_STREAM_FAILED` | `ModelStreamException` | provider `onError` 抛非 HelmException 且非 overflow |
| `ENGINE_INTERRUPTED` | `EngineInterruptedException` | 等待 stream 时 `InterruptedException` |
| `CONTEXT_OVERFLOW` | `ContextOverflowException` | 上下文超限（见 3.5 子分类） |
| `TOOL_INPUT_INVALID` | `ToolExecutionException` | tool input 不符 schema |
| `TOOL_OUTPUT_INVALID` | `ToolExecutionException` | tool output 为 null 或不可序列化 |
| `TOOL_NOT_FOUND` | `ToolExecutionException` | tool name 未注册 |
| `TOOL_EXECUTION_FAILED` | `ToolExecutionException` | tool 自身抛错（保留现有 code） |

### 3.4 ToolExecutionException code 扩展

现状：`ToolExecutionException` 硬编码 `code=TOOL_EXECUTION_FAILED`（`ToolExecutionException.java:7`）。

方案：增加接受 code 的构造器，保留旧构造器为 `TOOL_EXECUTION_FAILED` 别名（向后兼容）。

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ToolExecutionException extends HelmException {
    public static final String CODE_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    public static final String CODE_INPUT_INVALID = "TOOL_INPUT_INVALID";
    public static final String CODE_OUTPUT_INVALID = "TOOL_OUTPUT_INVALID";
    public static final String CODE_NOT_FOUND = "TOOL_NOT_FOUND";

    public ToolExecutionException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        this(CODE_EXECUTION_FAILED, message, details, developerDetails);
    }

    public ToolExecutionException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }
}
```

`AgentRuntime.executeTool`（`AgentRuntime.java:310-311`）中"Tool not found"改用 `ToolExecutionException(CODE_NOT_FOUND, ...)`。

### 3.5 ContextOverflow 分类

`ContextOverflowException` 已存在但 details 无子分类。方案：通过 `details` 中 `kind` 字段区分三类，不引入子类（避免子类爆炸）。

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ContextOverflowException extends HelmException {
    public static final String KIND_PROMPT = "PROMPT_OVERFLOW";
    public static final String KIND_COMPLETION = "COMPLETION_OVERFLOW";
    public static final String KIND_ACCUMULATED = "ACCUMULATED_OVERFLOW";

    public ContextOverflowException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("CONTEXT_OVERFLOW", message, details, developerDetails);
    }

    public static ContextOverflowException prompt(String message, Map<String, Object> details) {
        return new ContextOverflowException(
                message,
                withKind(details, KIND_PROMPT),
                Map.of());
    }

    public static ContextOverflowException completion(String message, Map<String, Object> details) {
        return new ContextOverflowException(
                message,
                withKind(details, KIND_COMPLETION),
                Map.of());
    }

    public static ContextOverflowException accumulated(String message, Map<String, Object> details) {
        return new ContextOverflowException(
                message,
                withKind(details, KIND_ACCUMULATED),
                Map.of());
    }

    private static Map<String, Object> withKind(Map<String, Object> details, String kind) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(details);
        merged.put("kind", kind);
        return Map.copyOf(merged);
    }
}
```

**触发判定时机**：

| kind | 触发点 | 判定方式 |
| --- | --- | --- |
| `PROMPT_OVERFLOW` | provider `onError` 返回 context-length 错误，且 messages + instructions 已超模型上下文窗口 | provider adapter 把 provider 错误码（OpenAI `context_length_exceeded`、Anthropic 输入超限）映射为 `ContextOverflowException.prompt(...)` |
| `COMPLETION_OVERFLOW` | 单轮 model stream 完成（`onComplete`），但 `text` 长度 + 累积上下文超过模型 completion 上限 | provider adapter 在 stream 完成后检测 `finish_reason=length`（OpenAI）/ `stop_reason=max_tokens`（Anthropic）映射为 `ContextOverflowException.completion(...)` |
| `ACCUMULATED_OVERFLOW` | turn 开始前，`TokenCounter`（可选 SPI）预估累积 messages token 数超过模型上下文窗口的预留比例（默认 90%） | `TurnRunner` 在 `provider.stream` 之前调用 `TokenCounter`，超阈则抛 `ContextOverflowException.accumulated(...)` |

`TokenCounter` SPI（可选，未提供时 ACCUMULATED_OVERFLOW 不触发）：

```java
package io.agent.helm.core.model;

import io.agent.helm.core.message.HelmMessage;

/**
 * 可选 token 计数器。provider adapter 实现并注册到 AgentEngineRequest；
 * 未注册时 engine 不做预检，只依赖 provider 错误映射识别 PROMPT_OVERFLOW。
 */
public interface TokenCounter {
    long count(HelmMessage message);

    long count(String text);
}
```

**为什么不直接用真实 token 计数**：tokenizer 依赖 provider SDK（tiktoken / anthropic SDK），不能进 `helm-core`。SPI 留口子，由 provider adapter 实现，保持 core-first。

### 3.6 Tool input/output 校验

`JsonSchemaValidator` SPI（在 `helm-core/type`）：

```java
package io.agent.helm.core.type;

import java.util.List;

/**
 * 把 Object 校验为符合 JsonSchema 的实例。返回错误列表，空列表表示通过。
 * 默认实现为 DefaultJsonSchemaValidator（在 helm-core，基于 JsonSchema 当前类型系统）。
 * 当 JsonSchema 类型系统扩展（见 03-json-schema-extensions.md）时，校验器随之扩展。
 */
public interface JsonSchemaValidator {
    List<ValidationError> validate(Object input, JsonSchema schema);

    /** 默认抛 ToolExecutionException(CODE_INPUT_INVALID) 当 input 不符合 schema。 */
    default void requireValid(Object input, JsonSchema schema, String toolName) {
        List<ValidationError> errors = validate(input, schema);
        if (!errors.isEmpty()) {
            throw new io.agent.helm.core.error.ToolExecutionException(
                    io.agent.helm.core.error.ToolExecutionException.CODE_INPUT_INVALID,
                    "Tool input does not match schema",
                    java.util.Map.of(
                            "tool", toolName,
                            "errors", errors.stream().map(ValidationError::message).toList()),
                    java.util.Map.of("input", String.valueOf(input)));
        }
    }
}
```

```java
package io.agent.helm.core.type;

public record ValidationError(String path, String message) {}
```

**校验落点**：在 `helm-agent-engine` 内部用 package-private 的 `ToolInvocationGuard` 包装 `ToolExecutor`：

```java
package io.agent.helm.engine;

import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.core.type.JsonSchemaValidator;
import java.util.List;
import java.util.Map;

/**
 * 在 ToolExecutor.execute 之前校验 input，之后校验 output。
 * 失败分别抛 TOOL_INPUT_INVALID / TOOL_OUTPUT_INVALID。
 */
final class ToolInvocationGuard {
    private final JsonSchemaValidator validator;

    ToolInvocationGuard(JsonSchemaValidator validator) {
        this.validator = validator;
    }

    void requireValidInput(Object input, ToolDescriptor descriptor) {
        validator.requireValid(input, descriptor.inputSchema(), descriptor.name());
    }

    void requireValidOutput(Object output, String toolName) {
        if (output == null) {
            throw new ToolExecutionException(
                    ToolExecutionException.CODE_OUTPUT_INVALID,
                    "Tool returned null output",
                    Map.of("tool", toolName),
                    Map.of());
        }
        // 至少要求可序列化为 JSON：基本类型、record、Map、List。
        // 真实 JSON 序列化在 provider adapter / store 层会再做一次，这里只做 type check，
        // 避免把不可序列化的对象（如 InputStream、Connection）带入消息历史。
        if (!isJsonSerializable(output)) {
            throw new ToolExecutionException(
                    ToolExecutionException.CODE_OUTPUT_INVALID,
                    "Tool output is not JSON-serializable",
                    Map.of("tool", toolName, "type", output.getClass().getName()),
                    Map.of());
        }
    }

    private static boolean isJsonSerializable(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Map
                || value instanceof List
                || value instanceof Record
                || value.getClass().isEnum();
    }
}
```

**调用顺序**（在 `AgentEngine.run` 内）：

```java
for (ModelStreamEvent.ToolCallRequested toolCall : result.toolCalls()) {
    ToolDescriptor descriptor = resolveDescriptor(request.tools(), toolCall.name());
    guard.requireValidInput(toolCall.input(), descriptor);              // 在 user code 前

    listener.onEvent(new EngineEvent.ToolStarted(turn, now, toolCall.name(), toolCall.id()));
    Object output;
    try {
        output = request.toolExecutor().execute(operationId, toolCall.name(), toolCall.input());
        guard.requireValidOutput(output, toolCall.name());              // 在 user code 后
        listener.onEvent(new EngineEvent.ToolEnded(turn, now, duration, toolCall.name(), toolCall.id()));
    } catch (ToolExecutionException e) {
        listener.onEvent(new EngineEvent.ToolFailed(turn, now, duration, toolCall.name(), toolCall.id(),
                e.code(), e.getMessage()));
        throw e;
    } catch (RuntimeException | Exception e) {
        listener.onEvent(new EngineEvent.ToolFailed(turn, now, duration, toolCall.name(), toolCall.id(),
                "TOOL_EXECUTION_FAILED", e.getMessage()));
        throw new ToolExecutionException("Tool execution failed",
                Map.of("tool", toolCall.name(), "operationId", operationId, "message", messageOf(e)),
                Map.of());
    }
    messages.add(...);
}
```

注意：`operationId` 不再写死 `"engine"`，由 `AgentEngineRequest` 传入。

### 3.7 Token usage 聚合

`TokenUsage` 增加静态累加方法（保持 record 不可变）：

```java
package io.agent.helm.core.model;

public record TokenUsage(long inputTokens, long outputTokens) {
    public static TokenUsage zero() {
        return new TokenUsage(0, 0);
    }

    public static TokenUsage sum(TokenUsage a, TokenUsage b) {
        return new TokenUsage(
                a.inputTokens() + b.inputTokens(),
                a.outputTokens() + b.outputTokens());
    }
}
```

`TurnResult` 增加 `usage` 字段（`TurnRunner` 捕获 `ModelStreamEvent.Completed`）：

```java
record TurnResult(
        String text,
        List<ModelStreamEvent.ToolCallRequested> toolCalls,
        io.agent.helm.core.model.TokenUsage usage) {
    TurnResult {
        usage = java.util.Objects.requireNonNullElseGet(usage, io.agent.helm.core.model.TokenUsage::zero);
    }
}
```

`TurnRunner.onNext` 增加 `Completed` 分支：

```java
} else if (event instanceof ModelStreamEvent.Completed completed) {
    if (usageRef.get() == null) {
        usageRef.set(completed.usage());
    }
}
```

`AgentEngineResult` 增加累计 `TokenUsage`：

```java
public record AgentEngineResult(
        String text,
        java.util.List<HelmMessage> messages,
        io.agent.helm.core.model.TokenUsage totalUsage) {
    public AgentEngineResult {
        messages = List.copyOf(messages);
        totalUsage = java.util.Objects.requireNonNullElseGet(totalUsage, io.agent.helm.core.model.TokenUsage::zero);
    }

    /** 向后兼容：旧调用方仅传 text + messages，usage 默认 zero。 */
    public AgentEngineResult(String text, java.util.List<HelmMessage> messages) {
        this(text, messages, io.agent.helm.core.model.TokenUsage.zero());
    }
}
```

`AgentEngine.run` 在每个 turn 后累加：

```java
TokenUsage total = TokenUsage.zero();
for (int turn = 0; turn < request.maxTurns(); turn++) {
    TurnResult result = turnRunner.run(...);
    total = TokenUsage.sum(total, result.usage());
    // ...
}
return new AgentEngineResult(finalText, messages, total);
```

`AgentRuntime.executePrompt`（`AgentRuntime.java:216-221`）的 `OPERATION_SUCCEEDED` payload 增加 usage：

```java
appendEventSafely(
        operationId,
        null,
        2,
        RuntimeEventType.OPERATION_SUCCEEDED,
        Map.of(
                "text", String.valueOf(result.text()),
                "inputTokens", result.totalUsage().inputTokens(),
                "outputTokens", result.totalUsage().outputTokens()));
```

### 3.8 AgentEngine API 演进

`AgentEngineRequest` 增加可选字段（保持 record 兼容性靠新静态工厂 + 缺省值）：

```java
public record AgentEngineRequest(
        ModelRef model,
        String instructions,
        java.util.List<ToolDescriptor> tools,
        java.util.List<HelmMessage> messages,
        ModelProvider provider,
        ToolExecutor toolExecutor,
        Duration timeout,
        int maxTurns,
        String operationId,                                  // 新增：用于事件关联与日志
        io.agent.helm.engine.event.EngineEventListener listener,  // 新增：engine 事件回调
        io.agent.helm.core.type.JsonSchemaValidator schemaValidator,  // 新增：tool input 校验
        io.agent.helm.core.model.TokenCounter tokenCounter) {       // 新增：可选 ACCUMULATED_OVERFLOW 预检
    // 紧凑构造器：null → 默认值
    public AgentEngineRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        operationId = operationId == null ? "engine" : operationId;
        listener = listener == null ? EngineEventListener.noop() : listener;
        schemaValidator = schemaValidator == null ? new io.agent.helm.core.type.DefaultJsonSchemaValidator() : schemaValidator;
        tokenCounter = tokenCounter == null ? null : tokenCounter;  // null 表示不做预检
    }

    /** 旧 8 参数构造器（向后兼容）： */
    public AgentEngineRequest(
            ModelRef model, String instructions, java.util.List<ToolDescriptor> tools,
            java.util.List<HelmMessage> messages, ModelProvider provider,
            ToolExecutor toolExecutor, Duration timeout, int maxTurns) {
        this(model, instructions, tools, messages, provider, toolExecutor, timeout, maxTurns,
                "engine", EngineEventListener.noop(), null, null);
    }
}
```

`AgentEngine.run` 签名不变（仍 `run(AgentEngineRequest)`），所有新增能力通过 request 字段传入。

### 3.9 配置项

| 项 | 默认 | 来源 | 说明 |
| --- | --- | --- | --- |
| `engine.maxTurns` | `8`（`AgentRuntime.DEFAULT_MAX_TURNS`） | `AgentRuntime` | 已存在 |
| `engine.timeout` | `Duration.ofSeconds(30)`（`AgentRuntime.DEFAULT_TIMEOUT`） | `AgentRuntime` | 已存在 |
| `engine.tokenCounter` | `null`（不预检） | `AgentRuntime.Builder` | 新增，由应用注入 provider 实现 |
| `engine.schemaValidator` | `DefaultJsonSchemaValidator` | `AgentRuntime.Builder` | 新增，测试可替换 |
| `engine.eventListener` | 内部桥接实现（runtime → store + observer） | `AgentRuntime` 自建 | 不暴露给应用 |

## 4. 数据流与时序

### 4.1 turn 循环时序（含事件序列）

```text
AgentEngine.run(AgentEngineRequest)
  │
  ├── for turn in [0, maxTurns):
  │     │
  │     ├── listener.onEvent(TurnStarted{turnIndex=turn, at=now})
  │     │
  │     ├── [if tokenCounter != null]
  │     │     estimated = tokenCounter.count(messages...)
  │     │     if estimated > window * 0.9:
  │     │       listener.onEvent(TurnFailed{..., errorCode=CONTEXT_OVERFLOW, ...})
  │     │       throw ContextOverflowException.accumulated(...)
  │     │
  │     ├── listener.onEvent(ModelStarted{turnIndex=turn, modelRef=...})
  │     ├── TurnRunner.run(provider, modelRequest)
  │     │     ├── provider.stream(modelRequest).subscribe(subscriber)
  │     │     │     ├── onNext(ContentDelta) → text.append
  │     │     │     ├── onNext(ToolCallRequested) → toolCalls.add
  │     │     │     ├── onNext(Completed) → usageRef.set(usage)   // 新增
  │     │     │     ├── onError(throwable)
  │     │     │     │     ├── 若 throwable 是 ContextOverflowException → 直接抛
  │     │     │     │     ├── 若 throwable 是 HelmException → 直接抛
  │     │     │     │     └── 否则包成 ModelStreamException(developerDetails=throwable)
  │     │     │     └── onComplete → done.countDown
  │     │     │
  │     │     ├── [timeout] done.await(timeout)
  │     │     │     超时 → cancel subscription + throw TurnTimeoutException
  │     │     │     中断 → cancel subscription + throw EngineInterruptedException
  │     │     │
  │     │     └── return TurnResult(text, toolCalls, usage)
  │     │
  │     ├── listener.onEvent(ModelEnded{turnIndex=turn, duration, modelUsage=usage})
  │     │
  │     ├── total = TokenUsage.sum(total, turnResult.usage)
  │     │
  │     ├── if toolCalls.isEmpty():
  │     │     messages.add(assistant)
  │     │     listener.onEvent(TurnEnded{turnIndex=turn, duration, turnUsage=usage})
  │     │     return AgentEngineResult(text, messages, total)
  │     │
  │     └── for toolCall in toolCalls:
  │           │
  │           ├── descriptor = resolveDescriptor(tools, toolCall.name)
  │           │     if descriptor == null:
  │           │       listener.onEvent(ToolFailed{..., errorCode=TOOL_NOT_FOUND, ...})
  │           │       throw ToolExecutionException(CODE_NOT_FOUND, ...)
  │           │
  │           ├── guard.requireValidInput(toolCall.input(), descriptor)  // 失败抛 TOOL_INPUT_INVALID
  │           │
  │           ├── listener.onEvent(ToolStarted{turnIndex, toolName, toolCallId})
  │           ├── try:
  │           │     output = toolExecutor.execute(operationId, toolCall.name(), toolCall.input())
  │           │     guard.requireValidOutput(output, toolCall.name())  // 失败抛 TOOL_OUTPUT_INVALID
  │           │     listener.onEvent(ToolEnded{..., duration})
  │           ├── catch ToolExecutionException:
  │           │     listener.onEvent(ToolFailed{..., errorCode=e.code()})
  │           │     throw
  │           ├── catch RuntimeException | Exception:
  │           │     listener.onEvent(ToolFailed{..., errorCode=TOOL_EXECUTION_FAILED})
  │           │     throw ToolExecutionException(...)
  │           │
  │           └── messages.add(assistant ToolCallBlock + tool result ToolResultBlock)
  │
  └── [loop exit without return]
        listener.onEvent(TurnFailed{turnIndex=maxTurns-1, errorCode=MAX_TURNS_EXCEEDED, ...})
        throw MaxTurnsExceededException(
                "Agent loop exceeded max turns",
                Map.of("maxTurns", maxTurns, "operationId", operationId),
                Map.of())
```

### 4.2 失败处理矩阵

| 失败场景 | 抛出异常 | code | 事件 |
| --- | --- | --- | --- |
| stream 超时 | `TurnTimeoutException` | `TURN_TIMEOUT` | `TurnFailed` + `ModelFailed` |
| stream 中断 | `EngineInterruptedException` | `ENGINE_INTERRUPTED` | `TurnFailed` + `ModelFailed` |
| provider `onError` 是 `ContextOverflowException` | 原异常透传 | `CONTEXT_OVERFLOW` | `TurnFailed` + `ModelFailed` |
| provider `onError` 是 `HelmException` | 原异常透传 | 原 code | `TurnFailed` + `ModelFailed` |
| provider `onError` 其他 `RuntimeException` | `ModelStreamException` | `MODEL_STREAM_FAILED` | `TurnFailed` + `ModelFailed` |
| provider `onError` 检查 `Exception` | `ModelStreamException` | `MODEL_STREAM_FAILED` | `TurnFailed` + `ModelFailed` |
| tool input schema 不符 | `ToolExecutionException` | `TOOL_INPUT_INVALID` | `ToolFailed`（在 `ToolStarted` 之前就失败，不发 `ToolStarted`） |
| tool output null / 不可序列化 | `ToolExecutionException` | `TOOL_OUTPUT_INVALID` | `ToolFailed` |
| tool name 未注册 | `ToolExecutionException` | `TOOL_NOT_FOUND` | `ToolFailed` |
| tool 自身抛错 | `ToolExecutionException` | `TOOL_EXECUTION_FAILED` | `ToolFailed` |
| maxTurns 用尽 | `MaxTurnsExceededException` | `MAX_TURNS_EXCEEDED` | `TurnFailed` |
| 累积 token 超阈 | `ContextOverflowException.accumulated` | `CONTEXT_OVERFLOW` (kind=ACCUMULATED) | `TurnFailed` |

### 4.3 事件序列示例（成功 2 turn）

```text
OPERATION_STARTED (runtime)
  TURN_STARTED (turn=0)
    MODEL_STARTED (turn=0)
    MODEL_SUCCEEDED (turn=0, usage={in:120,out:40})
    TOOL_STARTED (turn=0, tool=search)
    TOOL_SUCCEEDED (turn=0, tool=search, duration=15ms)
  TURN_SUCCEEDED (turn=0, usage={in:120,out:40})
  TURN_STARTED (turn=1)
    MODEL_STARTED (turn=1)
    MODEL_SUCCEEDED (turn=1, usage={in:200,out:30})
  TURN_SUCCEEDED (turn=1, usage={in:200,out:30})
OPERATION_SUCCEEDED (text=..., inputTokens=320, outputTokens=70)
```

注意 `OPERATION_*` 由 `AgentRuntime` 发，`TURN_*`/`MODEL_*`/`TOOL_*` 由 engine 通过 listener 桥接到 store。

## 5. 安全与边界

### 5.1 脱敏

- `EngineEvent` 不携带 raw input/output 文本，只携带 `toolName`、`toolCallId`、`turnIndex`、`usage`、`errorCode`、`message`。
- `ToolFailed.message` 取自异常 `getMessage()`，可能含敏感片段——listener 桥接到 `RuntimeEventRecord` 时统一过 `EventRedactor.redact(payload)`（`EventRedactor.java:12`）。
- `ToolExecutionException.developerDetails` 中可放 `input` 字面值，但 `EventRedactor` 会丢弃 `developerDetails` 键（`EventRedactor.java:45-47`），不会进 store。
- `OPERATION_SUCCEEDED` payload 只放 token 计数，不放 text 全文（已有现状）。

### 5.2 能力收窄

- `ToolInvocationGuard` 在 user tool code 前失败，避免无效输入触发副作用（如 SQL 注入字符串进入数据库 tool）。
- tool output 校验拒绝不可序列化对象（`InputStream`、`Connection`），避免脏对象进入消息历史后被持久化或转发到 provider。
- `JsonSchemaValidator` 拒绝 schema 未声明的字段（fail-closed，非 fail-open）。

### 5.3 错误映射

- engine 内部所有抛出均为 `HelmException` 子类（`EngineException` 或 `ToolExecutionException` 或 `ContextOverflowException`），不再有裸 `IllegalStateException`。
- `RuntimeErrorMapper.operationError`（`RuntimeErrorMapper.java:10-30`）已识别 `HelmException`，会取 `code` 与 `details`——新 code 自动被持久化与返回，无需改动 mapper。
- HTTP / CLI / SDK 层根据 `code` 映射 HTTP 状态与响应体，code 注册表见 3.3。

### 5.4 依赖守则

- `helm-agent-engine` 现有依赖：仅 `helm-core`。新增 `EngineEvent` / `EngineEventListener` / `ToolInvocationGuard` 都在 engine 包内，不引入新 maven 依赖。
- `helm-core` 新增 `JsonSchemaValidator` / `ValidationError` / `DefaultJsonSchemaValidator` / `EngineException` 层级 / `TokenCounter` / `TokenUsage.sum`——均为纯 Java，无新依赖。
- `helm-runtime` 增加一个 `EngineEventListener` 桥接实现（package-private），把 `EngineEvent` 转 `RuntimeEventRecord` 并 `appendEvent` + 通知 `RuntimeEventObserver`。

### 5.5 listener 异常隔离

- `EngineEventListener.onEvent` 抛出的异常被 engine 内部 try/catch 吞掉并记 log（不能影响控制流）。
- listener 不可阻塞 engine（实现方应异步处理或快速 append）。
- 若 listener 是 store.appendEvent 失败，沿用 `appendEventSafely`（`AgentRuntime.java:352-363`）的"事件持久化失败不影响 operation 结果"原则。

## 6. 测试策略

### 6.1 契约测试

`JsonSchemaValidatorContractTest`（在 `helm-core` 发布 test-jar，未来 JDBC/OTel adapter 也可复用）：

- 校验 `String` schema 拒绝 `Integer`、`null`。
- 校验 `object` schema 拒绝缺 required 字段的对象。
- 校验 `array` schema 拒绝元素类型不符。
- 校验 `null` input 与 schema `nullable=false` 不兼容。

`EngineEventListenerContractTest`（在 `helm-agent-engine` 发布 test-jar）：

- `TurnStarted` 后必跟 `TurnEnded` 或 `TurnFailed`，不交叉。
- `ToolStarted` 后必跟 `ToolEnded` 或 `ToolFailed`。
- `ModelStarted` 后必跟 `ModelEnded` 或 `ModelFailed`。
- `turnIndex` 单调非减。
- listener 抛异常不影响 engine 控制。

### 6.2 单元测试

在 `helm-agent-engine/src/test/java/`：

- `AgentEngineTest`：
  - happy path 1 turn（无 tool call）：emit `TurnStarted`/`ModelStarted`/`ModelEnded`/`TurnEnded`，result.totalUsage 等于单轮 usage。
  - happy path 2 turn 1 tool call：事件序列如 4.3 示例。
  - maxTurns 用尽：抛 `MaxTurnsExceededException`，code=`MAX_TURNS_EXCEEDED`，发 `TurnFailed`。
- `TurnRunnerTest`：
  - stream 超时：抛 `TurnTimeoutException`，发 `ModelFailed`。
  - stream 中断：抛 `EngineInterruptedException`。
  - provider `onError` 是 `ContextOverflowException`：透传，details.kind=PROMPT_OVERFLOW。
  - provider `onError` 是 `RuntimeException`：包成 `ModelStreamException`。
  - `Completed` 事件携带 usage：`TurnResult.usage` 等于该值。
  - `Completed` 缺失：`TurnResult.usage` 为 `TokenUsage.zero()`（fail-safe）。
- `ToolInvocationGuardTest`：
  - valid input → no throw。
  - invalid input → `ToolExecutionException(code=TOOL_INPUT_INVALID)`，在 user code 前抛出。
  - null output → `ToolExecutionException(code=TOOL_OUTPUT_INVALID)`。
  - 不可序列化 output → `ToolExecutionException(code=TOOL_OUTPUT_INVALID)`。
- `TokenUsageTest`：`sum` 与 `zero` 正确性。

### 6.3 FakeProvider 约定

`FakeProvider`（`helm-runtime/src/main/java/io/agent/helm/runtime/FakeProvider.java`）已有 `enqueue(ModelStreamEvent...)` 与 `failWith(RuntimeException)`。新增测试需要：

- enqueue `ModelStreamEvent.Completed(usage)` 验证 usage 聚合。
- `failWith(new ContextOverflowException.prompt(...))` 验证 overflow 透传。
- `failWith(new HelmException(...){})` 验证 HelmException 透传。

无需改动 `FakeProvider` 本身——它已经支持任意 `ModelStreamEvent` 与任意 `RuntimeException`。

### 6.4 集成测试

在 `helm-runtime/src/test/java/`：

- `AgentRuntimeEngineEventsTest`：跑一个完整 prompt，验证 `getOperationEvents(operationId)` 包含 `TURN_*`/`MODEL_*`/`TOOL_*` 事件且序列稳定。
- `AgentRuntimeUsageAggregationTest`：验证 `OPERATION_SUCCEEDED` payload 含 `inputTokens`/`outputTokens`，且与多 turn 累加一致。
- `AgentRuntimeToolValidationTest`：注册一个 schema 要求 `String` 但 tool 接收 `Integer` 的 fixture，验证在 user tool code 前失败，事件含 `TOOL_FAILED` errorCode=`TOOL_INPUT_INVALID`。

## 7. 验收标准

对齐 `docs/roadmap.md` M3 验收 + 本组件交付：

- [ ] `AgentEngine.run` 与 `TurnRunner.run` 全部抛出均为 `HelmException` 子类，无裸 `IllegalStateException`（grep 验证）。
- [ ] engine 控制流错误 code 稳定：`TURN_TIMEOUT` / `MAX_TURNS_EXCEEDED` / `MODEL_STREAM_FAILED` / `ENGINE_INTERRUPTED` / `CONTEXT_OVERFLOW`。
- [ ] tool input 不符 schema 时抛 `ToolExecutionException(code=TOOL_INPUT_INVALID)`，在 user tool code 前抛出（单测覆盖：tool `execute` 永不被调用）。
- [ ] tool output null 或不可序列化时抛 `ToolExecutionException(code=TOOL_OUTPUT_INVALID)`。
- [ ] tool not found 时抛 `ToolExecutionException(code=TOOL_NOT_FOUND)`（替代当前 `code=TOOL_EXECUTION_FAILED`）。
- [ ] `AgentEngineResult.totalUsage` 等于各 turn `TurnResult.usage` 之和。
- [ ] `OPERATION_SUCCEEDED` 事件 payload 含 `inputTokens` / `outputTokens`。
- [ ] `ContextOverflowException` details 含 `kind` 字段，取值为 `PROMPT_OVERFLOW` / `COMPLETION_OVERFLOW` / `ACCUMULATED_OVERFLOW` 之一。
- [ ] `EngineEventListener` 可接收全部 9 种 `EngineEvent` 子类型。
- [ ] `getOperationEvents` 返回的事件序列满足 6.1 契约（start → ended/failed，turnIndex 单调）。
- [ ] `EngineEventListener.onEvent` 抛异常不影响 engine 控制流（单测覆盖）。
- [ ] `mvn verify` 全绿，spotless 通过。
- [ ] 无新增 `helm-core` 对 runtime/engine/HTTP/CLI/Spring/SDK/JDBC/logging 依赖。

## 8. 风险与未决项

### 8.1 未决项

1. **`EngineEventListener` 落点**：本设计放在 `helm-agent-engine`（理由：engine 专属事件）。备选：放 `helm-core`（理由：与 `RuntimeEventObserver` 同级，便于 OTel/metrics adapter 直接消费）。决策标准：是否有 core 之外的模块需要直接监听 engine 事件而不经过 runtime 桥接。**当前倾向：放 `helm-agent-engine`**，待 #11 api governance 评审。

2. **`JsonSchemaValidator` 是否需要 SPI**：本设计提为 SPI（可替换实现）。备选：作为 `JsonSchema` 的实例方法。决策标准：是否需要不同 adapter 用不同校验实现（如 OTel adapter 用严格校验、runtime 用宽松校验）。**当前倾向：SPI，提供默认实现**。

3. **`TokenCounter` 是否进 M3**：本设计提为 SPI，但 tokenizer 实现依赖 provider SDK，不能进 core。决策标准：M3 是否需要真实 ACCUMULATED_OVERFLOW 检测，还是只定义 SPI 留到 M4 provider 实现。**当前倾向：M3 仅定义 SPI + null default，真实实现留 M4**。

4. **`ToolExecutionException` code 扩展方式**：本设计用"构造器重载 + 常量"。备选：sealed 子类层级（`ToolInputInvalidException` / `ToolOutputInvalidException` / `ToolNotFoundException`）。决策标准：调用方是否需要按具体类型 catch。**当前倾向：构造器重载**，调用方按 code 区分即可，避免子类爆炸。

5. **`AgentEngineResult` 兼容性**：本设计新增 `totalUsage` 字段并提供旧构造器。备选：新增 `AgentEngineResultV2`。决策标准：pre-1.0 是否允许 record 字段扩展。**当前倾向：直接扩展 + 提供旧构造器**，依赖 pre-1.0 兼容策略（#11 待定）。

6. **`operationId` 传入方式**：本设计通过 `AgentEngineRequest.operationId` 传入。备选：从 `ModelRequest` 推断或新增 `EngineContext` 参数对象。**当前倾向：扩 `AgentEngineRequest`**，最小改动。

7. **`MaxTurnsExceededException` 是否应触发 tool execution**：当前设计是循环结束就抛，最后一 turn 的 tool call 已执行。备选：在循环开始前检查 maxTurns 是否合理。**当前倾向：保持现状（已执行 tool 不可回滚）**，developerDetails 中记录已执行 turn 数。

### 8.2 风险

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| `JsonSchema` 类型系统（#3）尚未扩展 Map/enum/optional，导致 `JsonSchemaValidator` 现阶段校验能力有限 | 中 | M3 内同步推进 #3 至少覆盖 Map/optional；本设计 SPI 不变，校验实现随 schema 扩展 |
| `EngineEventListener` 桥接实现同步 append store，可能阻塞 engine 线程 | 中 | 沿用 `appendEventSafely` 吞异常；后续 #8 metrics 可改异步 |
| provider 错误码 → `ContextOverflowException.kind` 映射散落在各 provider adapter，可能不一致 | 中 | 在 `helm-core` 提供 `ContextOverflowMapper` 静态工具，provider adapter 复用 |
| `TokenUsage` 累加溢出（极长 conversation） | 低 | `long` 足够；若需要可加 `Math.addExact` 显式 overflow |
| `ToolInvocationGuard.isJsonSerializable` 用 `Record`/`Map`/`List` 启发式判断，可能误判用户自定义可序列化类型 | 中 | 文档说明：tool output 推荐用 record/Map/List；若需自定义类型，应用层自行 JSON 序列化后返回 String |
| `EngineEvent` 9 个子类型未来可能膨胀（如 `SkillStarted`） | 低 | sealed interface permits 列表显式，扩展需改接口；与 `RuntimeEventType` 一一对应 |
| `AgentEngineRequest` 字段从 8 增至 12，调用方构造冗长 | 中 | 提供 `Builder`（M3 内补充） |

## 9. 与其他组件的关系

### 9.1 依赖

- **#3 JsonSchema 扩展**（强依赖）：`JsonSchemaValidator` 的校验能力受限于 `JsonSchema` 类型系统。M3 内 #3 至少要支持 Map / optional / nested record，否则 tool input 校验只能覆盖基本类型。本设计的 SPI 形态不依赖 #3 的具体扩展，但验收（invalid tool input 测试）需要 #3 提供足够 schema 表达力。
- **#11 API governance**（弱依赖）：error code 注册表（3.3 节）需对齐 #11 的稳定 code 规则。在 #11 定稿前，本设计的 code 列表为 proposed。
- **`helm-core` 现有 `RuntimeEventType`**（已就绪）：`TURN_*` / `MODEL_*` / `TOOL_*` 已预定义（`RuntimeEventType.java:10-18`），无需新增枚举。

### 9.2 被依赖

- **#1 Streaming API**：streaming 暴露需要消费 `EngineEvent`（尤其是 `ModelEnded.usage` 与 `ContentDelta`）。本设计的 `EngineEventListener` 是 streaming 转发的天然来源——streaming adapter 可包装 listener 把事件转发到调用方。`AgentEngineResult.totalUsage` 也作为 stream 完成后的累计快照。
- **#8 Metrics & OpenTelemetry**：metrics observer 需要按 `RuntimeEventType.TURN_SUCCEEDED` / `MODEL_SUCCEEDED` / `TOOL_SUCCEEDED` 统计 duration、按 `*_FAILED` 统计 error code、按 `OPERATION_SUCCEEDED` payload 统计 token usage。本设计把 engine 事件桥接到 runtime event，metrics observer 直接复用 `RuntimeEventObserver`。
- **#5 Authorizer**：未来 tool 调用前的 authorizer hook 可挂在 `ToolInvocationGuard` 之前（本设计不实现，预留扩展点）。
- **#9 Durable scale**：turn journal（M11）需要 `EngineEvent` 流作为持久化源；`TurnStarted`/`TurnEnded` 是 turn 边界，`ToolStarted`/`ToolEnded` 是 tool 副作用边界（重放时需 idempotency）。

### 9.3 命名对齐

- 错误 code 与 `docs/contracts/11-api-governance.md`（待写）保持 SCREAMING_SNAKE_CASE。
- `EngineException` 子类命名对齐 `ContextOverflowException` / `ToolExecutionException` 现有 `XxxException` 模式。
- `EngineEvent` 子类命名对齐 `RuntimeEventType` 的 `Xxx_STARTED` / `Xxx_SUCCEEDED` / `Xxx_FAILED` 模式（注：`Ended` vs `Succeeded`——`EngineEvent` 用 `Ended` 因为同一事件既表示成功结束，duration 是核心字段；`RuntimeEventType` 用 `SUCCEEDED` 因为是 terminal 状态枚举。listener 桥接时 `TurnEnded` → `RuntimeEventType.TURN_SUCCEEDED`）。
- `TokenUsage.sum` / `TokenUsage.zero` 与 `Stream` / `Collector` 风格一致。

### 9.4 不引入的依赖

- 不依赖 `helidon`/`jackson`/`gson`/`tiktoken`/`antlr`——`JsonSchemaValidator` 默认实现纯 Java 反射，复用 `JsonSchema.fromType`（`JsonSchema.java:46-72`）已有的反射逻辑。
- 不依赖 `micrometer`/`opentelemetry`——metrics/trace 通过 `RuntimeEventObserver` 间接消费。
- 不依赖 `reactor`/`akka`——`Flow.Publisher` 是 JDK stdlib。
