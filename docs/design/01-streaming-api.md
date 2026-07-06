# 01 — Streaming API 暴露

> 组件编号：1 ｜ 来源：M3 engine hardening 留待项 ｜ 状态：proposed
>
> 关联组件：#2 engine hardening（engine 事件 / tool 校验 / usage 聚合）、#8 metrics & OpenTelemetry。

## 实现状态（2026-07-05）

**✓ 已实现（基础）**。`promptStream`（`@Preview`）+ `PromptStreamEvent` + SSE route + `helm-client` 增量流式 + 订阅取消已落地。仍待实现：durable 流式 chunk recovery（见 #9）。

## 1. 背景与目标

`ModelProvider.stream(ModelRequest)` 已经返回 `Flow.Publisher<ModelStreamEvent>`（`helm-core/src/main/java/io/agent/helm/core/model/ModelProvider.java:9`），流式能力在 SPI 层具备。但 runtime 与 engine 层把它折叠成同步结果：`AgentSessionApi.prompt(String)` 只返回 `PromptResult(operationId, text)`，调用方只能等整轮结束后拿到最终文本，无法增量收到 token、tool call、tool result 与 turn 边界。

本组件要解决的问题：

1. 让 `AgentSessionApi` 暴露一个**稳定**的流式 prompt API，向调用方增量推送 `ContentDelta`、`ToolCallRequested`、`ToolResultReady`、`TurnEnded`、`OperationCompleted`、`OperationFailed`。
2. 让 HTTP（SSE）、CLI、未来 client SDK 都能基于同一套流式事件，而不是各自定义不兼容的增量协议。
3. 保持 `prompt(String)` 同步方法不变，向后兼容；流式失败与同步失败走同一套 `HelmException` 与同一套 terminal record 持久化合约。

不在本组件范围：

- engine 内部 tool input/output 校验、token usage 聚合、context overflow 分类（属 #2 engine hardening）。
- 异步队列、lease/recovery、stream chunk recovery、cancellation（属 #9 durable scale runtime）。
- metrics / trace 关联字段定义（属 #8 metrics & OpenTelemetry），但本组件预留 observer hook。
- 远程 provider 的真实网络流式实现（属 provider adapter），本组件只约束 SPI 契约。

## 2. 现状与缺口

> **注**：以下缺口分析反映设计时的现状；当前实现状态见文首「实现状态（2026-07-05）」。

### 2.1 SPI 层已具备流式能力，但事件粒度不够

`ModelStreamEvent`（`helm-core/src/main/java/io/agent/helm/core/model/ModelStreamEvent.java:3-10`）是 sealed interface，permits：

- `ContentDelta(String text)`
- `ToolCallRequested(String id, String name, Object input)`
- `Completed(TokenUsage usage)`

这是 **model 层**的事件，只描述一次模型流的内容。它不包含：

- tool 执行结果（`ToolResultReady`）——因为 model provider 不知道 tool 是否被执行。
- turn 边界（`TurnEnded`）——因为多轮循环在 engine 层。
- operation 终态（`OperationCompleted` / `OperationFailed`）——因为 operation 生命周期在 runtime 层。

调用方（HTTP/CLI/SDK）需要的是 **operation 级别**的流式事件，而不是 model 级别。直接把 `ModelStreamEvent` 透传给调用方会泄漏内部层级、无法表达 tool result 与 turn 边界、也无法让调用方知道 operation 何时结束。

### 2.2 AgentSessionApi 与 AgentRuntime 完全同步

`AgentSessionApi`（`helm-core/src/main/java/io/agent/helm/core/agent/AgentSessionApi.java:3-5`）只有：

```java
PromptResult prompt(String text);
```

`AgentRuntime.prompt`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java:78-82`）同步调用 `executePrompt` 并返回最终 `PromptResult`。

`AgentRuntime.executePrompt`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java:127-240`）的关键步骤：

1. `activeSessions.putIfAbsent(sessionId, TRUE)`（行 129）抢 session 锁，否则抛 `SessionBusyException`。
2. `saveOperation(RUNNING)`（行 135-144）+ `appendEvent(OPERATION_STARTED)`（行 145-150）做 admission。
3. 加载 session、append user message、`saveSession(running)`（行 156-179）。
4. `engine.run(...)`（行 186-194）**同步阻塞**直到所有 turn 完成。
5. `saveSession(updated)` + `saveOperation(SUCCEEDED)` + `appendEventSafely(OPERATION_SUCCEEDED)`（行 196-221）。
6. `catch` 里 `saveOperation(FAILED)` + `appendEventSafely(OPERATION_FAILED)`（行 223-236）。
7. `finally` 释放 `activeSessions`（行 237-239）。

第 4 步是阻塞点：调用方在 `engine.run` 返回前拿不到任何增量。

### 2.3 TurnRunner 用 CountDownLatch 阻塞并丢弃增量

`TurnRunner.run`（`helm-agent-engine/src/main/java/io/agent/helm/engine/TurnRunner.java:14-74`）：

- `CountDownLatch done = new CountDownLatch(1)`（行 17）阻塞直到流结束。
- `onNext` 里把 `ContentDelta` 累积进 `StringBuilder text`（行 34-35），把 `ToolCallRequested` 收进 `toolCalls`（行 36-38）。
- `done.await(timeout)`（行 55-58）阻塞。
- 最终返回 `TurnResult(text, toolCalls)`（行 73），**原始事件序列被丢弃**。

`AgentEngine.run`（`helm-agent-engine/src/main/java/io/agent/helm/engine/AgentEngine.java:15-38`）在 turn 循环里调用 `turnRunner.run`，每次拿到 `TurnResult` 后同步执行 tool、append message、进入下一 turn。整个循环对外只暴露最终的 `AgentEngineResult(text, messages)`。

### 2.4 HTTP / Servlet / CLI 都基于同步 PromptResult

- `HelmHttpRoutes.promptHandler`（`helm-http-core/src/main/java/io/agent/helm/http/core/HelmHttpRoutes.java:46-56`）调用 `runtime.prompt(...)` 返回 `HelmHttpResponse.ok(json)`。
- `HelmHttpResponse`（`helm-http-core/src/main/java/io/agent/helm/http/core/HelmHttpResponse.java:7`）是 `record(int status, Map headers, String body)`，**只支持一次性 body**，不支持 chunked 输出。
- `HelmHttpServlet`（`helm-http-servlet/src/main/java/io/agent/helm/http/servlet/HelmHttpServlet.java:48-57`）用 `resp.getWriter().write(response.body())` 一次性写完，没有 async / SSE 支持。
- `RunCommand`（`helm-cli/src/main/java/io/agent/helm/cli/RunCommand.java`）只调用 `workflowRuntime().invoke`，且当前 CLI 没有 prompt 子命令。

### 2.5 roadmap 出处

- `docs/roadmap.md:84` 明确把"流式响应 API 暴露"列为"仍留待后续 milestone 的生产能力"。
- `docs/roadmap.md:289` M6 交付项：`event read API：先 paged events，后续可升级 stream/SSE`——本组件的 SSE 路由是这一项的 prompt 流式侧，event 读侧的 SSE 仍留给后续。
- `docs/design/README.md:26` 缺口表第 1 行：Streaming API 暴露，来源 M3。
- 依赖图（`docs/design/README.md:42`）：`2 engine hardening ─> 1 streaming api`，本组件依赖 #2 的 engine 事件 hook，但可先行落地 core/runtime/HTTP 的流式形态，engine 事件细节随后补齐。

## 3. 设计方案

### 3.1 PromptStreamEvent（helm-core `core.agent` 子包）

新增 sealed interface，描述 **operation 级别**的流式事件。放在 `io.agent.helm.core.agent`，与 `AgentSessionApi` / `PromptResult` 同包，因为它是 session API 的返回类型。

```java
package io.agent.helm.core.agent;

import io.agent.helm.core.model.TokenUsage;
import java.util.Map;

/**
 * Operation-level streaming event for {@link AgentSessionApi#promptStream(String)}.
 *
 * <p>Unlike {@code ModelStreamEvent} (which describes one model stream), this type spans the
 * full operation lifecycle: content deltas, tool calls, tool results, turn boundaries, and
 * terminal operation status. Terminal events ({@link OperationCompleted}, {@link OperationFailed})
 * are always emitted exactly once and signal that no further events will arrive.
 */
public sealed interface PromptStreamEvent
        permits PromptStreamEvent.ContentDelta,
                PromptStreamEvent.ToolCallRequested,
                PromptStreamEvent.ToolResultReady,
                PromptStreamEvent.TurnEnded,
                PromptStreamEvent.OperationCompleted,
                PromptStreamEvent.OperationFailed {

    /** Incremental assistant text. May be empty across a turn boundary. */
    record ContentDelta(String text) implements PromptStreamEvent {}

    /** The engine requests a tool call. Emitted before the tool is executed. */
    record ToolCallRequested(String id, String name, Object input) implements PromptStreamEvent {}

    /** A tool finished executing; output is the tool's return value (already redacted by the tool). */
    record ToolResultReady(String id, Object output, boolean isError) implements PromptStreamEvent {}

    /** One model turn finished; the next turn (if any) begins with the next ContentDelta. */
    record TurnEnded(int turn, String accumulatedText, TokenUsage usage) implements PromptStreamEvent {}

    /**
     * Terminal success. The session has been persisted, the operation record is SUCCEEDED, and
     * OPERATION_SUCCEEDED has been appended. Guaranteed to be the last event.
     */
    record OperationCompleted(String operationId, String text, TokenUsage totalUsage)
            implements PromptStreamEvent {}

    /**
     * Terminal failure. The operation record is FAILED and OPERATION_FAILED has been appended.
     * {@code details} are safe to expose (already redacted); {@code developerDetails} are not
     * included. Guaranteed to be the last event.
     */
    record OperationFailed(
            String operationId, String code, String message, Map<String, Object> details)
            implements PromptStreamEvent {}
}
```

设计决策：

- **不透传 `ModelStreamEvent`**：调用方需要 tool result 与 turn 边界，model 层事件表达不了。
- **`TurnEnded` 携带累积文本**：调用方若不关心增量，可以只看 `TurnEnded` 与 `OperationCompleted`，跳过 `ContentDelta`，降低消费门槛。
- **`OperationFailed` 只带 `details`，不带 `developerDetails`**：与 `HttpErrors.toResponse`（`helm-http-core/src/main/java/io/agent/helm/http/core/HttpErrors.java:19-28`）一致，developerDetails 不进流、不进 event、不进 HTTP。
- **terminal 事件保证发出且只发一次**：调用方据此释放资源，不需要额外的 onComplete 推断（Flow 的 onComplete 也会触发，但语义是"流关闭"，与"operation 成功"不等价——失败也关流）。

### 3.2 AgentSessionApi 新增 promptStream

```java
package io.agent.helm.core.agent;

import java.util.concurrent.Flow;

public interface AgentSessionApi {
    PromptResult prompt(String text);

    /**
     * Streams operation-level events for a prompt. The returned publisher is cold: nothing runs
     * until subscribed. Admission (operationId, RUNNING record, OPERATION_STARTED event) happens
     * on subscribe. The terminal event ({@link PromptStreamEvent.OperationCompleted} or
     * {@link PromptStreamEvent.OperationFailed}) is emitted before the Flow completes.
     *
     * <p>While streaming, the session is locked; a concurrent {@link #prompt} or
     * {@code promptStream} on the same session throws {@code SessionBusyException} synchronously
     * at subscribe time.
     */
    Flow.Publisher<PromptStreamEvent> promptStream(String text);
}
```

`AgentHarnessApi` 不变（`session(name).promptStream(text)` 自动可用）。`AgentSession`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentSession.java:6-23`）实现委托给 `AgentRuntime.promptStream`。

### 3.3 AgentRuntime.promptStream 实现

核心思路：复用 `executePrompt` 的 admission 前段，把 `engine.run` 替换为 `engine.runStream`，用一个 reactive 桥接器把 `EngineStreamEvent` 映射为 `PromptStreamEvent`，并在 terminal 事件前完成 terminal record 持久化。

```java
// helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java（新增方法）

public Flow.Publisher<PromptStreamEvent> promptStream(AgentPromptRequest request) {
    return subscriber -> {
        String operationId = "op_" + UUID.randomUUID();
        String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());
        // admission 在 subscribe 时执行，符合"先形成可检查记录再执行"
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            subscriber.onSubscribe(new NoopSubscription());
            subscriber.onError(new SessionBusyException(
                    "Session is busy", Map.of("sessionId", sessionId), Map.of()));
            return;
        }
        StreamContext ctx = beginStream(request, operationId, sessionId);
        engine.runStream(ctx.engineRequest())
                .subscribe(new EngineToPromptBridge(subscriber, ctx, this, sessionId));
    };
}
```

`beginStream` 提取自 `executePrompt` 的行 132-194（admission + saveOperation RUNNING + appendEvent OPERATION_STARTED + loadSession + append user message + saveSession running + 构建 `AgentEngineRequest`），返回一个不可变 `StreamContext`（持有 operationId、sessionId、running session state、engineRequest、startedAt）。

`EngineToPromptBridge` 是 `Flow.Subscriber<EngineStreamEvent>`，负责：

```java
// helm-runtime 内部，package-private
final class EngineToPromptBridge implements Flow.Subscriber<EngineStreamEvent> {
    private final Flow.Subscriber<? super PromptStreamEvent> downstream;
    private final StreamContext ctx;
    private final AgentRuntime runtime;
    private final String sessionId;
    private Flow.Subscription upstream;
    private final StringBuilder accumulated = new StringBuilder();
    private TokenUsage totalUsage = TokenUsage.ZERO;
    private int turn = 0;

    @Override public void onSubscribe(Flow.Subscription s) {
        this.upstream = s;
        downstream.onSubscribe(new ForwardingSubscription(s, this));
        s.request(1); // 一次一个事件，便于在 TurnEnded 后决定是否继续
    }

    @Override public void onNext(EngineStreamEvent event) {
        switch (event) {
            case EngineStreamEvent.ContentDelta d -> {
                accumulated.append(d.text());
                downstream.onNext(new PromptStreamEvent.ContentDelta(d.text()));
            }
            case EngineStreamEvent.ToolCallRequested t ->
                downstream.onNext(new PromptStreamEvent.ToolCallRequested(t.id(), t.name(), t.input()));
            case EngineStreamEvent.ToolResultReady r ->
                downstream.onNext(new PromptStreamEvent.ToolResultReady(r.id(), r.output(), r.isError()));
            case EngineStreamEvent.TurnEnded te -> {
                turn = te.turn();
                totalUsage = totalUsage.plus(te.usage());
                downstream.onNext(new PromptStreamEvent.TurnEnded(te.turn(), accumulated.toString(), te.usage()));
            }
            case EngineStreamEvent.EngineFailed ef -> {
                // terminal failure：先持久化 terminal record，再发 OperationFailed
                runtime.persistStreamFailure(ctx, ef, sessionId);
                downstream.onNext(new PromptStreamEvent.OperationFailed(
                        ctx.operationId(), ef.code(), ef.message(), ef.details()));
                downstream.onComplete();
                upstream.cancel();
                return; // 不再 request
            }
        }
        upstream.request(1);
    }

    @Override public void onError(Throwable t) {
        // engine 流异常（非 EngineFailed）：映射为 RUNTIME_ERROR
        Map<String, Object> error = RuntimeErrorMapper.operationError(t);
        runtime.persistStreamFailure(ctx, error, sessionId);
        downstream.onNext(new PromptStreamEvent.OperationFailed(
                ctx.operationId(),
                (String) error.getOrDefault("code", "RUNTIME_ERROR"),
                String.valueOf(t.getMessage()),
                (Map<String, Object>) error.getOrDefault("details", Map.of())));
        downstream.onComplete();
        runtime.releaseSession(sessionId);
    }

    @Override public void onComplete() {
        // engine 正常结束：先持久化 terminal record，再发 OperationCompleted
        runtime.persistStreamSuccess(ctx, accumulated.toString(), totalUsage, sessionId);
        downstream.onNext(new PromptStreamEvent.OperationCompleted(
                ctx.operationId(), accumulated.toString(), totalUsage));
        downstream.onComplete();
        runtime.releaseSession(sessionId);
    }
}
```

`persistStreamSuccess` / `persistStreamFailure` 提取自 `executePrompt` 的行 196-236（`saveSession(updated)` + `saveOperation(SUCCEEDED/FAILED)` + `appendEventSafely(OPERATION_SUCCEEDED/FAILED)`），保证 terminal record 持久化在 terminal event 发出之前。`releaseSession` 是 `activeSessions.remove(sessionId)` 的显式方法（替代 `executePrompt` 的 `finally` 块，因为 reactive 流没有同步 finally）。

**`prompt(String)` 复用流式实现**：可选优化——`prompt` 内部订阅 `promptStream` 并累积，保持行为一致。但为降低风险，本期保留 `executePrompt` 同步路径不变，`prompt` 与 `promptStream` 共享 `beginStream` 与 `persistStream*` 私有方法。

### 3.4 Engine 层：runStream + StreamingTurnRunner

新增 `AgentEngine.runStream(AgentEngineRequest)` 返回 `Flow.Publisher<EngineStreamEvent>`，与 `run` 并存（`run` 保留给同步 `prompt` 路径，避免破坏现有契约）。

```java
// helm-agent-engine/src/main/java/io/agent/helm/engine/EngineStreamEvent.java（新增，public）
package io.agent.helm.engine;

import io.agent.helm.core.model.TokenUsage;
import java.util.Map;

/** Engine-level streaming event. The runtime maps these to {@code PromptStreamEvent}. */
public sealed interface EngineStreamEvent
        permits EngineStreamEvent.ContentDelta,
                EngineStreamEvent.ToolCallRequested,
                EngineStreamEvent.ToolResultReady,
                EngineStreamEvent.TurnEnded,
                EngineStreamEvent.EngineFailed {

    record ContentDelta(String text) implements EngineStreamEvent {}
    record ToolCallRequested(String id, String name, Object input) implements EngineStreamEvent {}
    record ToolResultReady(String id, Object output, boolean isError) implements EngineStreamEvent {}
    record TurnEnded(int turn, String accumulatedText, TokenUsage usage) implements EngineStreamEvent {}
    record EngineFailed(String code, String message, Map<String, Object> details)
            implements EngineStreamEvent {}
}
```

```java
// helm-agent-engine/src/main/java/io/agent/helm/engine/AgentEngine.java（新增方法）
public Flow.Publisher<EngineStreamEvent> runStream(AgentEngineRequest request) {
    return new StreamingTurnRunner().runStream(request);
}
```

**`StreamingTurnRunner`**（package-private，与 `TurnRunner` 并存）替代 `CountDownLatch` 阻塞（`TurnRunner.java:17,54-63`），直接把 provider 的 `Flow.Publisher<ModelStreamEvent>` 转发为 `Flow.Publisher<EngineStreamEvent>`，并在 turn 之间插入 tool 执行：

```java
// helm-agent-engine/src/main/java/io/agent/helm/engine/StreamingTurnRunner.java（新增，package-private）
final class StreamingTurnRunner {
    Flow.Publisher<EngineStreamEvent> runStream(AgentEngineRequest request) {
        return subscriber -> {
            StreamState state = new StreamState(request, subscriber);
            subscriber.onSubscribe(state);
            // state 内部驱动：订阅 provider.stream，转发 ContentDelta/ToolCallRequested，
            // 在 provider onComplete 时判断是否有 tool calls：
            //   有 -> 执行 tool，发 ToolResultReady，进入下一 turn，重新订阅 provider
            //   无 -> 发 TurnEnded，engine onComplete
            // 任何 tool/model 异常映射为 EngineFailed
            state.startNextTurn();
        };
    }
}
```

`StreamState` 关键逻辑（伪代码，省略订阅状态机细节）：

1. 维护 `List<HelmMessage> messages`（可变，跨 turn 累积）、`int turn`、`StringBuilder accumulated`、`List<ToolCallRequested> pendingToolCalls`。
2. `startNextTurn()`：`turn++`，调用 `provider.stream(new ModelRequest(...))` 订阅。
3. provider `onNext(ContentDelta)`：`accumulated.append(text)`，向下游发 `EngineStreamEvent.ContentDelta(text)`。
4. provider `onNext(ToolCallRequested)`：加入 `pendingToolCalls`，向下游发 `EngineStreamEvent.ToolCallRequested`。
5. provider `onNext(Completed)`：记录 usage。
6. provider `onComplete()`：
   - 若 `pendingToolCalls` 非空：对每个 tool call，append `HelmMessage(ASSISTANT, ToolCallBlock)`，调用 `toolExecutor.execute(...)`，append `HelmMessage(TOOL, ToolResultBlock)`，向下游发 `EngineStreamEvent.ToolResultReady`。清空 `pendingToolCalls`，回到步骤 2 进入下一 turn。
   - 若 `pendingToolCalls` 为空：append `HelmMessage.assistant(accumulated)`，向下游发 `EngineStreamEvent.TurnEnded(turn, accumulated, usage)`，然后 `subscriber.onComplete()`。
7. `turn >= maxTurns`：发 `EngineFailed("MAX_TURNS_EXCEEDED", ...)`，`onComplete`。
8. 任何异常（provider onError / tool 异常 / timeout）：映射为 `EngineFailed` 或透传 `HelmException`，向下游发 `EngineFailed` 后 `onComplete`。**不抛裸 `IllegalStateException`**（对齐 #2 engine hardening 的"engine control flow 不泄漏裸 IllegalStateException"）。

与 #2 engine hardening 的对齐：

- `StreamingTurnRunner` 在每个 turn 与 tool 调用前后预留 `RuntimeEventType.TURN_STARTED/SUCCEEDED/FAILED`、`TOOL_STARTED/SUCCEEDED/FAILED` 的 observer hook 点（具体 observer SPI 由 #2 落地，本组件只保证调用位置）。
- `EngineFailed` 的 `code` 使用 #2 定义的 engine error taxonomy（`MAX_TURNS_EXCEEDED`、`MODEL_TIMEOUT`、`TOOL_EXECUTION_FAILED` 等），本组件不新造 code。
- 本组件先行落地 `runStream` 的 reactive 骨架，#2 后续在骨架内补 tool 校验、usage 聚合、overflow 分类。

### 3.5 HTTP SSE 路由（helm-http-core + helm-http-servlet）

#### 3.5.1 HelmHttpResponse 扩展支持流式 body

当前 `HelmHttpResponse`（`helm-http-core/src/main/java/io/agent/helm/http/core/HelmHttpResponse.java:7`）只有 `String body`。新增可选 `streamBody`：

```java
// helm-http-core/src/main/java/io/agent/helm/http/core/HelmHttpResponse.java（演进）
public record HelmHttpResponse(
        int status,
        Map<String, List<String>> headers,
        String body,
        Flow.Publisher<String> streamBody) {

    public HelmHttpResponse {
        headers = Map.copyOf(headers);
        body = body == null ? "" : body;
        streamBody = streamBody; // null 表示非流式
    }

    // 既有 factory 保留，streamBody = null
    public static HelmHttpResponse ok(String body) { return json(200, body); }
    public static HelmHttpResponse accepted(String body) { return json(202, body); }
    public static HelmHttpResponse json(int status, String body) {
        return new HelmHttpResponse(status, Map.of("Content-Type", List.of("application/json")), body, null);
    }

    /** SSE 流式响应：chunk publisher 发射已格式化的 SSE 帧（含 "event:" / "data:" 行）。 */
    public static HelmHttpResponse sse(Flow.Publisher<String> chunks) {
        return new HelmHttpResponse(
                200,
                Map.of(
                    "Content-Type", List.of("text/event-stream"),
                    "Cache-Control", List.of("no-cache"),
                    "Connection", List.of("keep-alive")),
                "",
                chunks);
    }
}
```

`HttpErrors.toResponse`（`HttpErrors.java:19-28`）继续返回非流式响应（`streamBody = null`），错误不进 SSE 流（错误在 admission 阶段同步返回 HTTP 错误响应；流式开始后的错误通过 SSE `event: OperationFailed` 帧 + 流关闭表达）。

`HelmHttpRouter.handle`（`HelmHttpRouter.java:26-44`）逻辑不变：handler 返回的 `HelmHttpResponse` 透传给 servlet；`streamBody != null` 由 servlet 层处理。

#### 3.5.2 SSE 路由

在 `HelmHttpRoutes.router`（`HelmHttpRoutes.java:31-43`）的 builder 里新增：

```java
.route(
    "POST",
    "/agents/{agent}/instances/{instance}/sessions/{session}/prompt/stream",
    promptStreamHandler(agentRuntime))
```

`promptStreamHandler` 返回 `HelmHttpResponse.sse(...)`，把 `Flow.Publisher<PromptStreamEvent>` 映射为 SSE 帧字符串：

```java
static HelmHttpHandler promptStreamHandler(AgentRuntime runtime) {
    return request -> {
        String text = readField(request.body(), "text");
        Flow.Publisher<PromptStreamEvent> stream = runtime.promptStream(new AgentPromptRequest(
                request.pathParam("agent"),
                request.pathParam("instance"),
                request.pathParam("session"),
                text));
        // SSE 帧：每个 PromptStreamEvent 序列化为
        //   event: <EventType>\n
        //   data: <json>\n\n
        Flow.Publisher<String> frames = new SseFramePublisher(stream);
        return HelmHttpResponse.sse(frames);
    };
}
```

`SseFramePublisher`（`helm-http-core` 内部，package-private）把每个 `PromptStreamEvent` 序列化为 `event: ContentDelta\ndata: {"text":"..."}\n\n` 格式，并在 terminal 事件后发 `data: [DONE]\n\n` 并关流。`Content-Type` 固定 `text/event-stream`。

**安全默认**：SSE 路由默认不注册。`HelmHttpRoutes.router` 增加重载 `router(agentRuntime, workflowRuntime, HttpFeatureSet features)`，`features.streamSse()` 默认 `false`；`helm-spring-boot-starter` 通过 `helm.http.streaming.enabled`（默认 `false`）开启。

#### 3.5.3 Servlet 适配（helm-http-servlet）

`HelmHttpServlet.service`（`HelmHttpServlet.java:30-46`）扩展：检测 `response.streamBody() != null` 时启用 Servlet async + SSE：

```java
// helm-http-servlet/src/main/java/io/agent/helm/http/servlet/HelmHttpServlet.java（演进）
@Override
protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    // ... 现有逻辑构建 HelmHttpRequest ...
    HelmHttpResponse response = router.handle(request);
    if (response.streamBody() != null) {
        startSse(req, resp, response);
    } else {
        write(resp, response); // 现有同步路径不变
    }
}

private static void startSse(HttpServletRequest req, HttpServletResponse resp, HelmHttpResponse response)
        throws IOException {
    AsyncContext ctx = req.startAsync();
    ctx.setTimeout(0); // SSE 不超时；超时由 runtime 的 engine timeout 控制
    ServletOutputStream out = resp.getOutputStream();
    resp.setStatus(response.status());
    response.headers().forEach((name, values) -> {
        for (String value : values) resp.addHeader(name, value);
    });
    response.streamBody().subscribe(new Flow.Subscriber<>() {
        private Flow.Subscription sub;
        @Override public void onSubscribe(Flow.Subscription s) {
            this.sub = s;
            s.request(1); // 一次一帧，靠 TCP 背压
        }
        @Override public void onNext(String frame) {
            try {
                out.print(frame);
                out.flush();
            } catch (IOException e) {
                sub.cancel();
                ctx.complete();
                return;
            }
            sub.request(1);
        }
        @Override public void onError(Throwable t) { ctx.complete(); }
        @Override public void onComplete() { ctx.complete(); }
    });
}
```

**`helm-http-core` 不依赖 Servlet**（`docs/design/README.md:175`）：`Flow.Publisher<String>` 是 JDK 类型，`HelmHttpServlet` 是唯一感知 Servlet 的模块，把 `Flow.Publisher<String>` 桥接到 `ServletOutputStream`。

### 3.6 CLI 流式选项

新增 `helm prompt` 子命令（当前 CLI 没有 prompt 命令，`RunCommand` 只跑 workflow），支持 `--stream`：

```java
// helm-cli/src/main/java/io/agent/helm/cli/PromptCommand.java（新增）
@Command(name = "prompt", description = "Send a prompt to an agent and print the response.")
final class PromptCommand extends HelmSubcommand {
    @Parameters(index = "0", description = "Agent name.")
    String agent;

    @Option(names = {"--instance"}, defaultValue = "default", description = "Agent instance id.")
    String instance;

    @Option(names = {"--session"}, defaultValue = "default", description = "Session name.")
    String session;

    @Parameters(index = "1", description = "Prompt text.")
    String text;

    @Option(names = "--stream", description = "Stream tokens to stdout as they arrive.")
    boolean stream;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            AgentSessionApi sessionApi = app.agentRuntime()
                    .harness(agent, instance)
                    .session(session);
            if (stream) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> error = new AtomicReference<>();
                sessionApi.promptStream(text).subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription sub;
                    @Override public void onSubscribe(Flow.Subscription s) { this.sub = s; s.request(1); }
                    @Override public void onNext(PromptStreamEvent e) {
                        switch (e) {
                            case PromptStreamEvent.ContentDelta d -> System.out.print(d.text());
                            case PromptStreamEvent.ToolCallRequested t -> System.err.println(
                                    "[tool] " + t.name() + " " + t.input());
                            case PromptStreamEvent.ToolResultReady r -> System.err.println(
                                    "[tool result] " + r.output());
                            case PromptStreamEvent.TurnEnded t -> {} // 静默
                            case PromptStreamEvent.OperationCompleted c -> {
                                System.out.println();
                                System.err.println("[operationId=" + c.operationId() + "]");
                            }
                            case PromptStreamEvent.OperationFailed f -> System.err.println(
                                    "[failed] " + f.code() + ": " + f.message());
                        }
                        System.out.flush();
                        sub.request(1);
                    }
                    @Override public void onError(Throwable t) { error.set(t); done.countDown(); }
                    @Override public void onComplete() { done.countDown(); }
                });
                done.await();
                if (error.get() != null) { System.err.println(toJson(HelmApps.errorBody(error.get()))); return 1; }
                return 0;
            }
            PromptResult result = sessionApi.prompt(text);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationId", result.operationId());
            body.put("text", result.text());
            System.out.println(toJson(body));
            return 0;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }
}
```

`--stream` 时 stdout 只打印增量 token（可管道），stderr 打印 tool / operation 元信息，最终 JSON 行打印 operationId。

### 3.7 背压策略

| 调用方 | request(n) 策略 | 说明 |
| --- | --- | --- |
| HTTP SSE servlet | `request(1)` per frame | 每帧写完 `flush` 后再 request 下一帧；TCP 满时 `out.print` 阻塞，自然反压上游。 |
| CLI `--stream` | `request(1)` per event | stdout 慢于 model 时阻塞，反压上游。 |
| client SDK（#6，未来） | `request(1)` 或 `request(N)` | 由 SDK 暴露 `onDelta` / `onComplete` 回调，默认 `request(1)`。 |
| `EngineToPromptBridge` | `request(1)` per `EngineStreamEvent` | 一次一个 engine 事件，便于在 `TurnEnded` / `EngineFailed` 后决定是否继续 request。 |
| `StreamingTurnRunner` 内部对 provider | 转发下游 demand | provider 的 `ModelStreamEvent` 流按下游 demand 拉；turn 之间 tool 执行暂停 demand。 |

`Long.MAX_VALUE`（unbounded）只在 CLI / 测试场景使用；生产 SSE 与 bridge 一律 `request(1)`，避免上游在下游慢时爆内存。

## 4. 数据流与时序

### 4.1 流式时序图

```text
HTTP/CLI/SDK            AgentRuntime            AgentEngine/StreamingTurnRunner       ModelProvider
     |                        |                              |                              |
     | promptStream(text)     |                              |                              |
     |----------------------->|                              |                              |
     |  (returns Publisher, cold)                            |                              |
     | subscribe()            |                              |                              |
     |----------------------->|                              |                              |
     |                        | admission:                   |                              |
     |                        |  op_id, saveOp RUNNING,      |                              |
     |                        |  appendEvent OP_STARTED,      |                              |
     |                        |  loadSession, append user,    |                              |
     |                        |  saveSession running,         |                              |
     |                        |  activeSessions.lock          |                              |
     |                        | runStream(engineRequest)      |                              |
     |                        |----------------------------->|                              |
     |                        |  (returns Publisher)         |                              |
     |                        | subscribe()                  |                              |
     |                        |------------------------------| stream(modelRequest)         |
     |                        |                              |----------------------------->|
     |                        |                              |  ContentDelta                |
     |  ContentDelta          |  ContentDelta                |<-----------------------------|
     |<-----------------------|<-----------------------------|                              |
     |  ...                   |                              |  Completed (usage)           |
     |                        |                              |<-----------------------------|
     |                        |                              |  (no tool calls)             |
     |                        |                              |  TurnEnded                   |
     |  TurnEnded             |  TurnEnded                  |                              |
     |<-----------------------|<-----------------------------|                              |
     |                        |  (engine onComplete)         |                              |
     |                        |  persistStreamSuccess:        |                              |
     |                        |    saveSession(updated),      |                              |
     |                        |    saveOp SUCCEEDED,         |                              |
     |                        |    appendEvent OP_SUCCEEDED, |                              |
     |                        |    activeSessions.unlock     |                              |
     |  OperationCompleted    |                              |                              |
     |<-----------------------|                              |                              |
     |  (Flow onComplete)     |                              |                              |
```

### 4.2 多轮 tool-call 时序（带 tool）

```text
provider onComplete (有 tool calls)
  -> StreamingTurnRunner:
       for each ToolCallRequested: emit ToolCallRequested, execute tool, emit ToolResultReady
       startNextTurn() (重新订阅 provider)
  -> 下一 turn 的 ContentDelta 继续转发
  -> 最终 turn 无 tool calls: emit TurnEnded, engine onComplete
  -> runtime: persistStreamSuccess, emit OperationCompleted
```

### 4.3 失败处理

| 失败点 | 行为 | terminal 事件 |
| --- | --- | --- |
| admission 阶段 session busy | `subscribe` 时同步 `onError(SessionBusyException)` | 无（HTTP 层映射 409） |
| provider `onError` | `StreamingTurnRunner` 发 `EngineFailed(code, ...)` | runtime 发 `OperationFailed`，`saveOp FAILED`，`appendEvent OP_FAILED` |
| tool 执行抛 `ToolExecutionException` | `StreamingTurnRunner` 发 `EngineFailed("TOOL_EXECUTION_FAILED", ...)` | 同上 |
| turn 超时 | `StreamingTurnRunner` 发 `EngineFailed("MODEL_TIMEOUT", ...)` | 同上 |
| `maxTurns` 超限 | `EngineFailed("MAX_TURNS_EXCEEDED", ...)` | 同上 |
| subscriber `onNext` 抛异常 | `StreamingTurnRunner` cancel upstream，`onError` | runtime `onError` 路径，`saveOp FAILED` |
| 持久化失败（saveSession/saveOp） | `appendEventSafely` 语义不变；terminal record 持久化失败**不静默吞**——发 `OperationFailed("PERSISTENCE_FAILED")` | runtime 发 `OperationFailed`，仍释放 session 锁 |

**terminal record 持久化顺序**（对齐 `docs/contracts/runtime-store.md:29` "terminal record 在返回或抛错前已持久化"）：

1. `saveSession(updated)`
2. `saveOperation(SUCCEEDED/FAILED)`
3. `appendEventSafely(OPERATION_SUCCEEDED/FAILED)`
4. **然后**才发 `OperationCompleted` / `OperationFailed`
5. **然后**才 `downstream.onComplete()` 与 `activeSessions.remove(sessionId)`

若步骤 1-3 抛异常，仍发 `OperationFailed("PERSISTENCE_FAILED")`，不假装成功。

### 4.4 并发与 session 锁

`activeSessions`（`AgentRuntime.java:51`）在 streaming 期间持续持有，从 `subscribe` 到 terminal 事件发出。期间任何 `prompt` / `promptStream` / `resetSession`（`AgentRuntime.java:120-125`）对同一 `sessionId` 都抛 `SessionBusyException`。锁在 terminal 事件后由 `EngineToPromptBridge.onComplete` / `onError` 显式释放（替代 `executePrompt` 的 `finally` 块）。

**风险**：subscriber 永不 request（不消费），provider 流永不完成 → session 锁泄漏。缓解：engine timeout（`AgentRuntime.java:42` `DEFAULT_TIMEOUT=30s`）由 `StreamingTurnRunner` 内部 `ScheduledExecutor` 触发 `EngineFailed("MODEL_TIMEOUT")`，强制释放锁。subscriber 侧不设额外 watchdog（属 #9 durable scale 的 cancellation 范围）。

## 5. 安全与边界

### 5.1 默认关闭

- SSE 路由默认不注册（`HttpFeatureSet.streamSse()` 默认 `false`）。
- `helm-spring-boot-starter` 通过 `helm.http.streaming.enabled=false` 控制。
- CLI `--stream` 是显式 opt-in flag。
- 同步 `prompt` 行为完全不变。

### 5.2 脱敏

- `ContentDelta`、`ToolCallRequested`、`ToolResultReady`、`TurnEnded` **不逐事件持久化到 event store**，避免事件爆炸（roadmap 原文"流式 ContentDelta 是否进 event store？建议只进 OperationCompleted 的 summary"）。只有 `OPERATION_STARTED` 与 `OPERATION_SUCCEEDED/FAILED` 进 store，与现有 `executePrompt` 行为一致。
- `OPERATION_SUCCEEDED` 的 payload 只带 final text（`AgentRuntime.java:221` 现有行为），不带逐 delta。
- `OperationFailed.details` 经过 `EventRedactor.redact`（`EventRedactor.java:12`）后再进 event store 与 HTTP 响应；`developerDetails` 不出现在 `PromptStreamEvent` 任何子类型（对齐 `HttpErrors.java:19-28` 不暴露 developerDetails）。
- tool output 由 tool 自身负责脱敏（现有 `executeTool` 行为不变）。

### 5.3 错误映射

`OperationFailed.code` 复用现有 `HelmException` code 体系（`HttpErrors.statusFor`，`HttpErrors.java:47-58`）：

| code | HTTP status | 来源 |
| --- | --- | --- |
| `SESSION_BUSY` | 409 | admission 同步阶段（HTTP 层直接返回，不进 SSE） |
| `PROVIDER_TIMEOUT` | 504 | `StreamingTurnRunner` timeout |
| `PROVIDER_ERROR` / `PROVIDER_RATE_LIMITED` | 502 / 429 | provider `onError` 透传 |
| `TOOL_EXECUTION_FAILED` | 500 | tool 执行异常 |
| `MAX_TURNS_EXCEEDED` | 500 | `StreamingTurnRunner` |
| `RUNTIME_ERROR` | 500 | 兜底 |
| `PERSISTENCE_FAILED` | 500 | terminal record 持久化失败 |

不新增 error code；与 #2 engine hardening 协调后可补充 `CONTEXT_OVERFLOW`（413，当前 `ContextOverflowException` 已存在）。

### 5.4 依赖守则

- `helm-core`：`PromptStreamEvent` 只用 `TokenUsage`（`core.model`）与 `Map`（JDK），不引入 runtime/engine/HTTP 依赖。✓
- `helm-agent-engine`：`EngineStreamEvent` 只用 `TokenUsage`（`core.model`），不依赖 runtime/HTTP。✓
- `helm-runtime`：`EngineToPromptBridge` 依赖 `EngineStreamEvent`（engine）与 `PromptStreamEvent`（core），符合现有 `helm-runtime → helm-core + helm-agent-engine` 依赖方向。✓
- `helm-http-core`：`SseFramePublisher` 只用 `PromptStreamEvent`（core）与 `Flow.Publisher`（JDK），不依赖 Servlet。✓
- `helm-http-servlet`：唯一感知 Servlet 的模块。✓
- `helm-cli`：依赖 `AgentSessionApi`（core）与 `Flow`（JDK）。✓

## 6. 测试策略

### 6.1 FakeProvider 流式契约测试

`FakeProvider`（`helm-runtime/src/main/java/io/agent/helm/runtime/FakeProvider.java:35-46`）已返回 `Flow.Publisher<ModelStreamEvent>` 且支持脚本化（`enqueue`、`failWith`）。`ModelProviderContractTest`（`helm-core/src/test/java/io/agent/helm/core/model/ModelProviderContractTest.java`）已覆盖 model 层流式契约。

新增 `PromptStreamContractTest`（abstract，放 `helm-runtime` test，或 `helm-runtime-testkit` 若已存在）：

```java
public abstract class PromptStreamContractTest {
    protected abstract AgentRuntime runtimeWith(FakeProvider provider);

    @Test
    void promptStreamEmitsContentDeltaThenOperationCompleted() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Hello "),
                new ModelStreamEvent.ContentDelta("world"),
                new ModelStreamEvent.Completed(new TokenUsage(3, 5)));
        AgentRuntime runtime = runtimeWith(provider);

        List<PromptStreamEvent> events = collect(
                runtime.harness("agent", "default").session("s1").promptStream("hi"));

        assertThat(events).hasSizeSatisfying(...);
        // ContentDelta("Hello "), ContentDelta("world"), TurnEnded(...), OperationCompleted(...)
        assertThat(events).last().isInstanceOf(PromptStreamEvent.OperationCompleted.class);
        assertThat(((OperationCompleted) events.getLast()).text()).isEqualTo("Hello world");
    }

    @Test
    void promptStreamEmitsToolCallThenToolResultThenTurnEnded() {
        provider.enqueue(
                new ToolCallRequested("c1", "echo", "input"),
                new Completed(usage1));
        provider.enqueue(
                new ContentDelta("done"),
                new Completed(usage2));
        // 断言事件序列：ToolCallRequested, ToolResultReady, ContentDelta("done"), TurnEnded, OperationCompleted
    }

    @Test
    void promptStreamMapsProviderErrorToOperationFailed() {
        provider.failWith(new ProviderException("rate limited", Map.of(), Map.of()));
        // 断言：OperationFailed(code=PROVIDER_ERROR...)，且流关闭后 operation 记录为 FAILED
    }

    @Test
    void promptStreamLocksSessionUntilTerminal() {
        // 第一个 promptStream 订阅但不 request，session 应 busy
        // 第二个 promptStream 应同步 onError(SessionBusyException)
    }

    @Test
    void promptStreamPersistsTerminalBeforeEmittingCompleted() {
        // 在 OperationCompleted 到达前，operation 记录应已是 SUCCEEDED
        // 用 CountDownLatch 在 OperationCompleted 前检查 store.loadOperation
    }

    @Test
    void promptStreamReleasesSessionLockOnError() {
        // provider 失败后，第二个 promptStream 应能成功订阅
    }

    @Test
    void promptStreamDoesNotPersistPerDeltaEvents() {
        // 完成后 eventsForOperation 只有 OPERATION_STARTED + OPERATION_SUCCEEDED
    }
}
```

### 6.2 SSE 端到端测试

在 `helm-http-servlet` test（参考现有 `HttpErrorContractTest`，用 Jetty）新增 `SseEndpointTest`：

```java
@Test
void promptStreamRouteEmitsSseFrames() throws Exception {
    // 启动 Jetty + HelmHttpServlet，注册 SSE 路由（features.streamSse=true）
    // POST /agents/.../prompt/stream，body={"text":"hi"}
    // 读取响应流，断言：
    //   - Content-Type: text/event-stream
    //   - 收到 event: ContentDelta\ndata: {"text":"..."}\n\n
    //   - 收到 event: OperationCompleted\ndata: {...}\n\n
    //   - 收到 data: [DONE]\n\n 后连接关闭
}

@Test
void sseRouteNotRegisteredByDefault() {
    // features.streamSse()=false 时，POST .../prompt/stream 返回 404
}

@Test
void sseSessionBusyReturns409BeforeStream() {
    // 占用 session 后，POST .../prompt/stream 返回 409 JSON（不进 SSE）
}
```

### 6.3 CLI 流式测试

参考现有 `HelmCliRunTest`，新增 `HelmCliPromptStreamTest`：用 `TestHelmApp` + `FakeProvider`，`helm prompt agent "hi" --stream --app ...`，断言 stdout 增量文本与 stderr tool/operationId 行。

### 6.4 单元测试覆盖

- `StreamingTurnRunner`：多轮 tool 循环、maxTurns、timeout、provider onError、tool 异常。
- `EngineToPromptBridge`：事件映射、accumulated 文本累积、usage 累加、terminal record 持久化顺序、session 锁释放。
- `SseFramePublisher`：SSE 帧格式（`event:` / `data:` / 空行 / `[DONE]`）、转义、空 delta。
- `HelmHttpResponse.sse`：Content-Type / Cache-Control / Connection header。

## 7. 验收标准

- [ ] `PromptStreamEvent` sealed interface 落地 `helm-core` `core.agent` 子包，6 个 permits 齐全。
- [ ] `AgentSessionApi.promptStream(String)` 返回 `Flow.Publisher<PromptStreamEvent>`；`prompt(String)` 行为不变。
- [ ] `AgentRuntime.promptStream` 复用 admission（saveOperation RUNNING + appendEvent OPERATION_STARTED + saveSession running），并在 terminal 事件前持久化 terminal record（saveSession + saveOperation SUCCEEDED/FAILED + appendEvent）。
- [ ] `AgentEngine.runStream` 返回 `Flow.Publisher<EngineStreamEvent>`；`StreamingTurnRunner` 不使用 `CountDownLatch` 阻塞。
- [ ] streaming 期间 session 持锁；并发 prompt/promptStream/resetSession 抛 `SessionBusyException`。
- [ ] terminal 事件（`OperationCompleted` / `OperationFailed`）保证发出且只发一次；发出后 session 锁释放。
- [ ] `ContentDelta` 等中间事件不进 event store；只有 `OPERATION_STARTED` + `OPERATION_SUCCEEDED/FAILED` 进 store。
- [ ] `helm-http-core` 新增 SSE 路由 `POST /agents/{agent}/instances/{instance}/sessions/{session}/prompt/stream`，`HelmHttpResponse` 支持 `streamBody`，`helm-http-core` 无 Servlet 依赖。
- [ ] `helm-http-servlet` 用 async + `ServletOutputStream` 桥接 `Flow.Publisher<String>` 到 SSE。
- [ ] SSE 路由默认不注册；需显式开启（`HttpFeatureSet` 或 starter property）。
- [ ] CLI `helm prompt ... --stream` 增量打印 token。
- [ ] `PromptStreamContractTest` 在 FakeProvider 上通过；SSE 端到端测试在 Jetty 上通过。
- [ ] `mvn verify` 全绿，`helm-core` 无新增生产依赖。
- [ ] `OperationFailed` 不携带 `developerDetails`；event payload 经 `EventRedactor` 脱敏。

## 8. 风险与未决项

1. **reactive 实现复杂度**：`StreamingTurnRunner` 的跨 turn 状态机（订阅 provider、缓冲 tool calls、在 onComplete 后执行 tool 并重新订阅）是本组件最复杂的实现点。未决：是否引入 `SubmissionPublisher` 或纯手写状态机。倾向手写（避免引入额外抽象），但需在实施阶段补足状态机测试。

2. **session 锁泄漏兜底**：subscriber 不消费或 provider 不完成时，靠 engine timeout（30s）释放锁。未决：是否在 `promptStream` 返回的 publisher 上叠加一个可被外部 cancel 的 watchdog（与 #9 durable scale 的 cancellation 重叠）。倾向本组件只靠 engine timeout，外部 cancel 留给 #9。

3. **`prompt` 与 `promptStream` 是否共享实现**：本期保留两条路径（`executePrompt` 同步、`promptStream` reactive），共享 `beginStream` / `persistStream*`。未决：是否在 #2 engine hardening 完成后把 `prompt` 改为 `promptStream` 的同步聚合，消除重复。倾向先并行，稳定后再合并。

4. **SSE 路由与 authorizer 的交互**：当前 `HelmAuthorizer` SPI（#5）尚未落地，SSE 路由暂无授权点。未决：SSE 路由的 `Accept: text/event-stream` 检查是否在 authorizer 之前。倾向 authorizer 落地后统一在路由前注入，本组件只预留 hook 点。

5. **HTTP/2 与反向代理缓冲**：SSE 经 nginx / ALB 时需 `proxy_buffering off`。未决：是否在文档中给出部署清单，还是留给 #10 release engineering。倾向本组件只写设计，部署清单留 #10。

6. **event store 是否需要 stream offset**：当前 `eventsForOperation` 返回全量。若未来要支持"从某个 offset 增量读 event"（M6 留待项），需在 `RuntimeStore` 加 offset 参数。本组件不动 store SPI，只暴露 prompt 流；event 读侧 SSE 留后续。

7. **`TurnEnded` 是否暴露 usage**：当前设计携带 `TokenUsage`。若 #2 engine hardening 决定 usage 只在 `OperationCompleted` 聚合，`TurnEnded` 可不带 usage。未决，依赖 #2。

8. **多 turn 中 `ContentDelta` 的累积语义**：`TurnEnded.accumulatedText` 是当前 turn 的累积还是整个 operation 的累积？当前设计是当前 turn。未决：调用方是否需要"截至当前的完整文本"。倾向当前 turn，整体文本由 `OperationCompleted.text` 提供。

## 9. 与其他组件的关系

| 组件 | 关系 | 说明 |
| --- | --- | --- |
| #2 engine hardening | 依赖（部分先行） | `StreamingTurnRunner` 预留 engine event hook 位置；`EngineFailed` code 复用 #2 的 error taxonomy；tool 校验、usage 聚合、overflow 分类由 #2 在 `StreamingTurnRunner` 内补齐。本组件先落地 reactive 骨架。 |
| #3 json schema 扩展 | 无直接依赖 | tool input/output 序列化用现有 Jackson；schema 扩展不影响流式形态。 |
| #5 authorizer / security context | 被依赖（预留） | SSE 路由未来需在 admission 前授权；本组件预留 hook，不实现。 |
| #6 HTTP client SDK | 被依赖 | client SDK 将基于 `PromptStreamEvent` 提供 `onDelta` / `onToolCall` 回调；本组件定义的事件类型是 SDK 的稳定契约。 |
| #7 rate limiting / admission | 被依赖 | rate limiter 未来在 `promptStream` 的 admission 阶段介入；本组件的 admission 流程（subscribe 时执行）为 rate limiter 提供插入点。 |
| #8 metrics & OpenTelemetry | 协作 | `RuntimeEventObserver`（`helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventObserver.java:8`）已存在；streaming 事件可经 observer 上报 metrics。本组件不新增 observer SPI，复用现有。metrics（stream duration、delta count、token usage）由 #8 在 observer 侧实现。 |
| #9 durable scale runtime | 后续 | stream chunk recovery、cancellation、per-session FIFO 队列由 #9 落地；本组件的 `promptStream` 是同步 reactive 流，#9 将其演进为 durable。`activeSessions` 锁在 #9 中可能被 lease 替代。 |
| 现有 `prompt` / `dispatch` | 共存 | `prompt(String)` 不变；`dispatch`（`AgentRuntime.java:84-95`）保持同步 admission 形态。`promptStream` 是新的流式入口，不替换 `dispatch`。 |

### 命名对齐

- `PromptStreamEvent` 与 `ModelStreamEvent` 平行命名（`*StreamEvent`），但前者在 `core.agent`（operation 级），后者在 `core.model`（model 级）。
- `EngineStreamEvent` 与 `PromptStreamEvent` 平行，前者 engine 级（`helm-agent-engine`），后者 operation 级（`helm-core`）。runtime 负责映射。
- `promptStream` 与 `prompt` 平行命名；`runStream` 与 `run` 平行。
- 错误 code 全部 `SCREAMING_SNAKE_CASE`，复用现有 `HelmException` 体系，不新造。
- HTTP 路径 `/prompt/stream` 与现有 `/prompt`（`HelmHttpRoutes.java:34`）平行。
