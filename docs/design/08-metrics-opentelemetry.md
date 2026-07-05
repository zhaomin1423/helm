# 08 — Metrics & OpenTelemetry

> 组件编号：#8 · 来源 milestone：M9 · 状态：proposed · 关联组件：#1 streaming、#2 engine hardening、#7 rate limiting、#11 api governance

## 1. 背景与目标

### 1.1 为什么需要

`helm-observability-logging`（`LoggingRuntimeObserver`，结构化 SLF4J，仅记元数据）是 Helm 当前唯一的可观测性 adapter。它解决了"操作过程能被结构化记录"的问题，但不能回答生产环境必须回答的四个问题：

1. **延迟分布**：operation / provider call / tool call 各自耗时长什么样？P95 在哪里？哪个 provider 慢？
2. **失败率与失败码分布**：哪种 `errorCode` 在涨？哪个 agent 失败率高？
3. **token 消耗**：input/output token 在哪些 model/provider 上累计最多？是否超 budget？
4. **链路关联**：一个 prompt（operation）下跑了哪些 turn、调了哪些 tool、每次 model call 用了多少 token——能不能用同一 traceId 串起来？

这四个问题都需要**数值指标**（metrics）和**链路追踪**（tracing）才能回答，单靠日志事件流不行。

同时，roadmap M9 的"安全默认"要求：默认日志只记录 metadata/summary；developer details 默认不输出；events/logs 默认脱敏。当前 `EventRedactor`（`helm-runtime`，package-private）只能按 key 名字启发式脱敏（`authorization` / `token` / `apiKey` 等），**无法处理业务字段**——例如 `Tool` 的 input/output record 里某个字段是用户手机号、某条 event payload 里的 `text` 是用户输入的 prompt 内容。需要给业务一个显式标注脱敏的注解。

### 1.2 目标

本组件目标是为 Helm 提供生产级 metrics 与 OpenTelemetry tracing 能力，对齐 `docs/roadmap.md` M9 验收：

| 目标 | 内容 |
| --- | --- |
| 复用 RuntimeEventObserver SPI | metrics observer 从 event payload 提取指标，不新增 SPI |
| metrics 指标集 | operation duration/failure、provider/tool latency、token usage、turn count、rate-limit reject |
| OpenTelemetry adapter 模块 | 新增 `helm-observability-opentelemetry`，同时发 metrics + tracing |
| trace 层级 | operation → turn → tool call / provider call，traceId/spanId 通过 OTel Context 传播，不污染 RuntimeEventRecord |
| content capture policy | `ContentCaptureLevel`（METADATA_ONLY / SUMMARY / FULL），默认 METADATA_ONLY |
| redaction 注解 | `@Redact` 注解 + `EventRedactor` 扩展，递归脱敏被标注字段 |
| 默认安全 | 默认只记 metadata、developer details 不输出、content 不进 metrics |

### 1.3 不解决什么

- 不替换 `LoggingRuntimeObserver`——logging 与 metrics/tracing 并存，都从同一 event 流消费（见 §3.1）。
- 不引入 distributed context propagation 跨进程协议——只在 JVM 内通过 OTel Context 传播；跨进程传播留待 HTTP client/server interceptor（属 #6/#11）。
- 不实现真实 token 计数器（tokenizer）——`TokenUsage` 由 provider adapter 通过 `ModelStreamEvent.Completed` 提供，本组件只聚合已有 usage。
- 不引入新的 runtime event 类型——`RuntimeEventType` 已有 `TURN_*` / `MODEL_*` / `TOOL_*`，本组件只消费这些事件。
- 不引入新的错误类型——错误 code 由 #2 engine hardening 定义。
- core 不依赖 OTel SDK——所有 OTel 依赖只在 `helm-observability-opentelemetry` adapter 模块（见 §5）。

### 1.4 出处

- `docs/roadmap.md` 第 4 节 M9 行：`proposed`，P1。
- `docs/roadmap.md` 第 3.1 节："仍留待后续 milestone 的生产能力：……metrics/OTel（M9）……"。
- `docs/roadmap.md` 第 2 节原则 5（事件优先）：metrics/tracing 基于同一套 runtime event taxonomy。
- `docs/roadmap.md` 第 2 节原则 6（安全默认）：events/logs 默认脱敏。
- `docs/design/README.md` 第 2 节缺口行 #8 引用本文件。

## 2. 现状与缺口

### 2.1 RuntimeEventObserver SPI（已存在）

`helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventObserver.java:8-10`：

```java
public interface RuntimeEventObserver {
    void onEvent(RuntimeEventRecord event);
}
```

- 单方法 SPI，传入 `RuntimeEventRecord(id, operationId, workflowRunId, sequence, type, payload, createdAt)`。
- 注释明确："events are already redacted before reaching observers"——observer 拿到的 payload 已经过 `EventRedactor.redact`。
- `LoggingRuntimeObserver`（`helm-observability-logging`）即此 SPI 的实现，按 type 路由到 INFO/DEBUG/WARN。
- `AgentRuntime.appendEvent`（`AgentRuntime.java:336-350`）在写 store 之前调用 `EventRedactor.redact(payload)`——脱敏发生在事件入口，不在 observer。

### 2.2 RuntimeEventType（已预定义但部分空置）

`helm-core/src/main/java/io/agent/helm/core/event/RuntimeEventType.java:3-25` 已预定义 23 种事件类型，覆盖 operation / workflow / turn / model / tool / skill / sandbox / error。

但 `AgentRuntime.executePrompt`（`AgentRuntime.java:127-240`）当前只发 `OPERATION_STARTED` / `OPERATION_SUCCEEDED` / `OPERATION_FAILED` 三种——`TURN_*` / `MODEL_*` / `TOOL_*` 的实际发射依赖 #2 engine hardening 完成（设计见 [`02-engine-hardening.md`](02-engine-hardening.md) §3.2 `EngineEvent` SPI 与 §3.6 事件→RuntimeEventRecord 映射）。

**本组件假设 #2 已完成**：turn/model/tool start/end/fail 事件已经在 RuntimeEventRecord 流里，payload 携带 `turnIndex`、`durationMs`、`toolName`、`modelRef`、`usage` 等字段（见 [`02-engine-hardening.md`](02-engine-hardening.md) §3.2 payload 映射表）。

### 2.3 LoggingRuntimeObserver（已存在）

`helm-observability-logging/src/main/java/io/agent/helm/observability/logging/LoggingRuntimeObserver.java:13-60`：

- 仅记录 `type` / `operationId` / `workflowRunId` / `sequence`，不记录 payload。
- 按 type 路由：error/failed → WARN；model/tool/skill → DEBUG；其它 → INFO。
- 是 `RuntimeEventObserver` 的唯一实现，证明 SPI 可用。

缺口：**不发 metrics、不发 span、不记 latency、不记 token**。

### 2.4 EventRedactor（已存在，仅启发式）

`helm-runtime/src/main/java/io/agent/helm/runtime/EventRedactor.java:9-99`（package-private final class）：

- 按 key 名字启发式脱敏：`developerDetails` 整体丢弃；`authorization` / `token` / `password` / `secret` / `apiKey` / `accessToken` 替换为 `[REDACTED]`。
- 递归处理 `Map` / `List`。
- **缺口 1**：只能按 key 名字脱敏，业务字段（如 `text`、`prompt`、tool input/output 里的 `phone`）无法被识别为敏感。
- **缺口 2**：是 package-private，外部 adapter（OTel observer）无法复用其递归脱敏逻辑。
- **缺口 3**：没有注解机制——业务代码无法显式标注"这个 record 字段是敏感的"。

### 2.5 TokenUsage（已存在）

`helm-core/src/main/java/io/agent/helm/core/model/TokenUsage.java:1`：`record TokenUsage(long inputTokens, long outputTokens)`。

当前被 `TurnRunner` 丢弃（[`02-engine-hardening.md`](02-engine-hardening.md) §2.2 行 104 缺口），需 #2 完成后才会在 `MODEL_SUCCEEDED` / `TURN_SUCCEEDED` 事件 payload 里出现 `inputTokens` / `outputTokens`。

### 2.6 缺口汇总

| 缺口 | 来源 | 本文档解决位置 |
| --- | --- | --- |
| 无 metrics（duration、failure code、provider/tool latency、token usage） | M9 交付项 | §3.2 |
| 无 trace correlation fields（operation→turn→tool→provider span 层级） | M9 交付项 | §3.4 |
| 无 content capture policy（METADATA_ONLY / SUMMARY / FULL） | M9 交付项 | §3.5 |
| 无 redaction annotation（`@Redact` 注解） | M9 交付项 | §3.3 |
| 无 `helm-observability-opentelemetry` 模块 | roadmap 模块图 post-preview | §3.6 |
| `EventRedactor` package-private，不可复用 | 本组件 | §3.3（升级为 public + 注解驱动） |

## 3. 设计方案

### 3.1 决策：复用 RuntimeEventObserver，不新增 MetricsCollector SPI

**候选方案 A**：新增 `MetricsCollector` SPI（`void onMetric(MetricEvent event)`），runtime 在关键节点直接调用。

**候选方案 B**：复用 `RuntimeEventObserver`，metrics observer 从 `RuntimeEventRecord` payload 提取指标。

| 维度 | A（新 SPI） | B（复用 RuntimeEventObserver） |
| --- | --- | --- |
| SPI 数量 | +1 | 0 |
| 事件→指标耦合 | 强（runtime 必须知道哪些点要发 metric） | 弱（observer 解释事件流） |
| 与 logging observer 对称 | 不对称（两个 SPI 两个调用点） | 对称（同一 SPI 多实现） |
| 漏发风险 | 高（新增 metric 点要改 runtime） | 低（observer 升级即可） |
| 重复 payload | 否 | 是（observer 重新解析 payload） |
| trace context 传播 | 需在 SPI 调用点显式传 | 复用 event 顺序，但 span 仍需 Context（见 §3.4） |

**决策：采用方案 B（复用 RuntimeEventObserver）。**

理由：

1. **事件优先原则**（roadmap 第 2 节原则 5）：metrics/tracing 基于同一套 runtime event taxonomy。新增 SPI 违反此原则。
2. **与 logging observer 对称**：logging observer 已证明从 event 流消费可行；metrics observer 同样模式，减少 SPI 数量。
3. **可演进**：metric 指标集扩展不需要改 runtime，只升级 observer。
4. **payload 解析成本可接受**：observer 只提取少量字段（durationMs / usage / errorCode），不做完整反序列化。
5. **trace context 通过 OTel Context 传播**，不依赖 SPI 调用点（见 §3.4）——方案 B 的"span 传播"问题不成立。

代价：observer 必须容忍 payload schema 演进（字段缺失时跳过指标，不抛错）。本设计在 `OpenTelemetryRuntimeObserver` 中统一处理（见 §3.4）。

### 3.2 Metrics 指标集

所有指标名以 `helm.` 为前缀，避免与业务应用指标冲突。指标只在 `OpenTelemetryRuntimeObserver` 中定义，core 不感知指标。

| 指标名 | type | unit | labels | 触发事件 | 描述 |
| --- | --- | --- | --- | --- | --- |
| `helm.operation.duration` | histogram | ms | `agent`, `status`, `code` | `OPERATION_SUCCEEDED` / `OPERATION_FAILED` | 一次 prompt 端到端耗时 |
| `helm.operation.failure` | counter | 1 | `code`, `agent` | `OPERATION_FAILED` | 失败 operation 计数（按 error code 分桶） |
| `helm.provider.duration` | histogram | ms | `provider`, `model`, `status` | `MODEL_SUCCEEDED` / `MODEL_FAILED` | 单次 model call 耗时（含 streaming 聚合） |
| `helm.tool.duration` | histogram | ms | `tool`, `status` | `TOOL_SUCCEEDED` / `TOOL_FAILED` | 单次 tool 调用耗时 |
| `helm.token.usage.input` | counter | 1 | `provider`, `model` | `MODEL_SUCCEEDED`（payload 含 `usage`） | 累计 input token |
| `helm.token.usage.output` | counter | 1 | `provider`, `model` | `MODEL_SUCCEEDED`（payload 含 `usage`） | 累计 output token |
| `helm.turn.count` | histogram | 1 | `agent` | `OPERATION_SUCCEEDED` | 一次 operation 的 turn 数（payload 携带 `turnCount`，由 #2 在 `OPERATION_SUCCEEDED` 写入） |
| `helm.rate_limit.rejected` | counter | 1 | `dimension` | `OPERATION_FAILED`（payload 含 `errorCode=RATE_LIMITED`） | rate limit 拒绝次数，对齐 #7 |

**label 取值约定**：

- `agent`：`AgentConfig.name`，从 `RuntimeEventRecord.operationId` 反查 store 得到 agent 名（observer 不直接持有 agent；见 §3.4 设计——`OpenTelemetryRuntimeObserver` 接受一个 `Function<String, String>` 把 operationId 映射到 agent 名，runtime 注册时注入）。
- `status`：`success` / `failure`。
- `code`：失败时取 payload `errorCode`（如 `MAX_TURNS_EXCEEDED` / `TURN_TIMEOUT` / `CONTEXT_OVERFLOW` / `RATE_LIMITED`），成功时空字符串。
- `provider` / `model`：从 `MODEL_STARTED` payload 的 `modelRef` 拆分（`provider:model` 格式，由 #2 定义）。
- `tool`：`toolName`。
- `dimension`：rate limit 拒绝维度（`session` / `agent` / `global`，由 #7 定义）。

**label 数量约束**：单指标 label 基数上限——`provider` 受 model allowlist 约束（<20），`model` 同上，`agent` 受应用 agent 数约束（<100），`tool` 受 tool 数约束（<1000 但建议按 agent 聚合）。高基数 label（如 `operationId` / `toolCallId`）**不进 metrics**，只进 trace span attribute。

### 3.3 Redaction 注解与 EventRedactor 扩展

#### 3.3.1 `@Redact` 注解（helm-core）

放在 `helm-core/src/main/java/io/agent/helm/core/event/Redact.java`，注解业务 record 字段：

```java
package io.agent.helm.core.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component, field, or map entry key as sensitive. The runtime {@code EventRedactor}
 * recursively traverses annotated records and replaces annotated components' values with {@code [REDACTED]}
 * before the value reaches any {@link RuntimeEventObserver} or the {@code RuntimeStore}.
 *
 * <p>Apply to:
 * <ul>
 *   <li>Tool input record components — tool input is often user data (phone, email, PII).</li>
 *   <li>Tool output record components — tool output may echo sensitive input or fetch secrets.</li>
 *   <li>Event payload map keys — when constructing a payload map, wrap values via
 *       {@link Redacted#of(Object)} to mark them for redaction.</li>
 * </ul>
 *
 * <p>This annotation is in {@code helm-core} so tool authors can mark their records without depending
 * on {@code helm-runtime}. The runtime provides the reflective redactor; OTel adapter reuses the result.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Redact {
    /** Optional reason for redaction, surfaced only in developer tooling, never in events. */
    String value() default "";
}
```

**示例：业务 Tool 标注脱敏**

```java
package com.example.helm.tools;

import io.agent.helm.core.event.Redact;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.TypeDescriptor;

public record LookupCustomerInput(String customerId, @Redact("PII") String phoneNumber) {}

public record LookupCustomerOutput(String name, @Redact("PII") String address, String tier) {}

public final class LookupCustomerTool implements Tool<LookupCustomerInput, LookupCustomerOutput> {
    @Override
    public String name() { return "lookup_customer"; }

    @Override
    public TypeDescriptor<LookupCustomerInput> inputType() { return TypeDescriptor.of(LookupCustomerInput.class); }

    @Override
    public TypeDescriptor<LookupCustomerOutput> outputType() { return TypeDescriptor.of(LookupCustomerOutput.class); }

    @Override
    public LookupCustomerOutput execute(ToolContext context, LookupCustomerInput input) {
        // ... business logic ...
        return new LookupCustomerOutput("Alice", "123 Main St", "gold");
    }
}
```

事件 payload 序列化时，`LookupCustomerInput{customerId="c-1", phoneNumber="555-1234"}` 会被脱敏为 `{customerId="c-1", phoneNumber="[REDACTED]"}`——**永远不进 store、不进 observer、不进 metrics、不进 log**。

#### 3.3.2 `Redacted` 标记容器（helm-core）

业务在构造 `Map<String, Object>` payload 时，若某条 entry 想脱敏，用 `Redacted.of(value)` 包装：

```java
package io.agent.helm.core.event;

/** Opaque wrapper marking a value for redaction. {@code EventRedactor} replaces it with {@code [REDACTED]}. */
public record Redacted(Object value) {
    public static Redacted of(Object value) { return new Redacted(value); }
}
```

`EventRedactor` 遇到 `Redacted` 实例直接替换为 `"[REDACTED]"`，不递归其内部 value。

#### 3.3.3 `EventRedactor` 升级为 public + 注解驱动（helm-runtime）

将 `helm-runtime` 中的 `EventRedactor` 从 package-private 升级为 public final class，新增注解驱动的递归脱敏：

```java
package io.agent.helm.runtime;

import io.agent.helm.core.event.Redact;
import io.agent.helm.core.event.Redacted;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redacts sensitive content from event payloads and tool records before they reach {@link
 * io.agent.helm.core.event.RuntimeEventObserver} instances or the {@link
 * io.agent.helm.core.store.RuntimeStore}.
 *
 * <p>Three redaction mechanisms, applied in order:
 * <ol>
 *   <li><b>Annotation-driven</b>: record components annotated {@link Redact} are recursively replaced with
 *       {@code [REDACTED]}. This is the primary mechanism for business fields.</li>
 *   <li><b>Heuristic key-name matching</b>: keys named {@code authorization}, {@code token},
 *       {@code password}, {@code secret}, {@code apiKey}, {@code accessToken} are redacted, and
 *       {@code developerDetails} is dropped entirely. Backwards-compatible with the prior implementation.</li>
 *   <li><b>Explicit {@link Redacted} wrappers</b>: payload builders wrap sensitive values via
 *       {@link Redacted#of(Object)} for ad-hoc redaction of map entries.</li>
 * </ol>
 *
 * <p>This class is public so adapters (e.g. {@code helm-observability-opentelemetry}) and tests can reuse
 * the same redaction logic. Adapters must NOT re-redact — observers receive already-redacted payloads.
 */
public final class EventRedactor {
    private EventRedactor() {}

    public static Map<String, Object> redact(Map<String, Object> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        payload.forEach((key, value) -> redactEntry(redacted, String.valueOf(key), value));
        return Map.copyOf(redacted);
    }

    /** Recursively redacts a record's annotated components, returning a defensive copy. */
    public static Object redactRecord(Object record) {
        if (record == null) {
            return null;
        }
        if (record.getClass().isRecord()) {
            return redactRecordImpl(record);
        }
        return record;
    }

    // ... existing heuristic key-name matching logic retained verbatim ...
    // ... redactEntry / redactValue / isDeveloperDetails / isSecretKey / normalizeKey / keyParts ...

    private static Object redactRecordImpl(Object record) {
        Class<?> type = record.getClass();
        RecordComponent[] components = type.getRecordComponents();
        // Build a new record instance with annotated components replaced by "[REDACTED]".
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            Object value;
            try {
                value = c.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                // Reflective failure must never change operation outcome; fall back to redacting the whole record.
                return "[REDACTED]";
            }
            if (c.isAnnotationPresent(Redact.class)) {
                args[i] = "[REDACTED]";
            } else if (value != null && value.getClass().isRecord()) {
                args[i] = redactRecordImpl(value);
            } else {
                args[i] = value;
            }
        }
        try {
            return type.getDeclaredConstructor(componentTypes(components)).newInstance(args);
        } catch (ReflectiveOperationException e) {
            return "[REDACTED]";
        }
    }

    private static Class<?>[] componentTypes(RecordComponent[] components) {
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        return types;
    }
}
```

**关键点**：

- 注解驱动是**反射**实现，不引入注解处理器；record 反射成本可接受（事件流量 < 1k/s）。
- `Redacted` wrapper 与 `@Redact` 注解互补：注解用于业务 record 字段，wrapper 用于 ad-hoc map entry。
- 启发式 key-name 匹配**完全保留**（向后兼容 `LoggingRuntimeObserver` 已依赖的行为）。
- `EventRedactor.redactRecord` 是新 public 入口，供 `AgentRuntime` 在把 tool input/output 写入 event payload 之前调用。

**集成点**：`AgentRuntime.appendEvent`（`AgentRuntime.java:336-350`）当前调用 `EventRedactor.redact(payload)`，无需改动。但 #2 engine hardening 把 tool input/output 写入 `TOOL_STARTED` / `TOOL_ENDED` payload 时，必须先 `redactRecord(input)` 再 `redact(payload)`——`EventRedactor.redact` 已递归处理 record，所以只需保证 record 进了 payload。

### 3.4 OpenTelemetryRuntimeObserver

放在 `helm-observability-opentelemetry/src/main/java/io/agent/helm/observability/opentelemetry/OpenTelemetryRuntimeObserver.java`。**同时发 metrics 与 tracing**——两者都是 OTel SDK 的能力，合在一个 observer 避免重复解析 event payload。

#### 3.4.1 接口形态

```java
package io.agent.helm.observability.opentelemetry;

import io.agent.helm.core.event.RuntimeEventObserver;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link RuntimeEventObserver} that emits OpenTelemetry metrics and traces from the runtime event stream.
 *
 * <p>Metrics: operation/provider/tool latency histograms, token counters, failure counters — see §3.2.
 * Tracing: spans form a hierarchy operation → turn → tool call / provider call, with traceId propagated
 * via OTel {@link Context}, never embedded in {@link RuntimeEventRecord} payload.
 *
 * <p>This observer is a pure consumer: it never re-redacts (events arrive already redacted by
 * {@code EventRedactor}) and never mutates the event. Failures to emit metrics/spans are swallowed
 * and logged at DEBUG, matching the "events must not change operation outcome" contract.
 *
 * <p>Thread-safety: OTel SDK is thread-safe; the observer holds no per-event mutable state except the
 * in-flight span map, which is keyed by {@code operationId + turnIndex + toolCallId} and uses a
 * concurrent map.
 */
public final class OpenTelemetryRuntimeObserver implements RuntimeEventObserver {

    private final Meter meter;
    private final Tracer tracer;
    private final Function<String, String> agentResolver;
    private final ContentCaptureLevel captureLevel;

    public OpenTelemetryRuntimeObserver(OpenTelemetry openTelemetry) {
        this(openTelemetry, operationId -> "", ContentCaptureLevel.METADATA_ONLY);
    }

    public OpenTelemetryRuntimeObserver(
            OpenTelemetry openTelemetry,
            Function<String, String> agentResolver,
            ContentCaptureLevel captureLevel) {
        this.meter = openTelemetry.getMeter("io.agent.helm");
        this.tracer = openTelemetry.getTracer("io.agent.helm", "0.1.0");
        this.agentResolver = agentResolver;
        this.captureLevel = captureLevel;
    }

    @Override
    public void onEvent(RuntimeEventRecord event) {
        switch (event.type()) {
            case "operation.started" -> startOperationSpan(event);
            case "operation.succeeded" -> endOperationSpan(event, StatusCode.OK, null);
            case "operation.failed" -> endOperationSpan(event, StatusCode.ERROR, errorCode(event));
            case "turn.started" -> startTurnSpan(event);
            case "turn.succeeded" -> endTurnSpan(event, StatusCode.OK);
            case "turn.failed" -> endTurnSpan(event, StatusCode.ERROR);
            case "model.started" -> startProviderSpan(event);
            case "model.succeeded" -> {
                endProviderSpan(event, StatusCode.OK);
                recordTokenUsage(event);
            }
            case "model.failed" -> endProviderSpan(event, StatusCode.ERROR);
            case "tool.started" -> startToolSpan(event);
            case "tool.succeeded" -> endToolSpan(event, StatusCode.OK);
            case "tool.failed" -> endToolSpan(event, StatusCode.ERROR);
            default -> { /* skill / sandbox / workflow events: no metric yet */ }
        }
    }

    // --- Span lifecycle ---
    // Spans are stored in a concurrent map keyed by (operationId, turnIndex[, toolCallId]).
    // Context.current() carries the parent span across threads within the operation's scope.

    private void startOperationSpan(RuntimeEventRecord event) {
        Span span = tracer.spanBuilder("helm.operation")
                .setAttribute("helm.operation.id", event.operationId())
                .setAttribute("helm.agent", agentResolver.apply(event.operationId()))
                .startSpan();
        SpanStore.put(event.operationId(), span);
    }

    private void endOperationSpan(RuntimeEventRecord event, StatusCode status, String code) {
        Span span = SpanStore.take(event.operationId());
        if (span == null) return;
        Duration duration = elapsedSince(event);
        span.setAttribute("helm.operation.duration_ms", duration.toMillis());
        span.setAttribute("helm.operation.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null) span.setAttribute("helm.operation.code", code);
        span.setStatus(status);
        span.end();
        recordOperationMetric(duration, event, code);
    }

    // startTurnSpan / endTurnSpan / startProviderSpan / endProviderSpan / startToolSpan / endToolSpan
    // follow the same pattern: child of parent operation span via SpanStore.parent(operationId),
    // attributes per §3.4.2.

    // --- Metrics ---
    private void recordOperationMetric(Duration duration, RuntimeEventRecord event, String code) {
        meter.histogramBuilder("helm.operation.duration")
                .setUnit("ms")
                .ofLongs()
                .build()
                .record(duration.toMillis(),
                        io.opentelemetry.api.common.Attributes.builder()
                                .put("agent", agentResolver.apply(event.operationId()))
                                .put("status", code == null ? "success" : "failure")
                                .put("code", code == null ? "" : code)
                                .build());
        if (code != null) {
            meter.counterBuilder("helm.operation.failure")
                    .ofLongs()
                    .build()
                    .add(1, io.opentelemetry.api.common.Attributes.builder()
                            .put("code", code)
                            .put("agent", agentResolver.apply(event.operationId()))
                            .build());
        }
    }

    private void recordTokenUsage(RuntimeEventRecord event) {
        Map<String, Object> payload = event.payload();
        Object usage = payload.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) return;
        long input = asLong(usageMap.get("inputTokens"));
        long output = asLong(usageMap.get("outputTokens"));
        String provider = String.valueOf(payload.getOrDefault("provider", "unknown"));
        String model = String.valueOf(payload.getOrDefault("model", "unknown"));
        meter.counterBuilder("helm.token.usage.input").ofLongs().build()
                .add(input, io.opentelemetry.api.common.Attributes.builder()
                        .put("provider", provider).put("model", model).build());
        meter.counterBuilder("helm.token.usage.output").ofLongs().build()
                .add(output, io.opentelemetry.api.common.Attributes.builder()
                        .put("provider", provider).put("model", model).build());
    }

    private static long asLong(Object v) { return v instanceof Number n ? n.longValue() : 0L; }
    private static Duration elapsedSince(RuntimeEventRecord e) { /* compute from createdAt vs SpanStore */ return Duration.ZERO; }
    private static String errorCode(RuntimeEventRecord e) { return String.valueOf(e.payload().getOrDefault("errorCode", "")); }
}
```

#### 3.4.2 Span 命名与属性

| span | 命名 | 关键 attribute | 父 span |
| --- | --- | --- | --- |
| operation | `helm.operation` | `helm.operation.id`, `helm.agent`, `helm.operation.status`, `helm.operation.code`, `helm.operation.duration_ms` | root（无 parent） |
| turn | `helm.turn` | `helm.turn.index`, `helm.turn.status`, `helm.turn.duration_ms` | operation span |
| model call | `helm.provider.call` | `helm.provider`, `helm.model`, `helm.provider.status`, `helm.provider.duration_ms` | turn span |
| tool call | `helm.tool.call` | `helm.tool`, `helm.tool.call_id`, `helm.tool.status`, `helm.tool.duration_ms` | turn span |

**层级实现**：`SpanStore`（observer 内部 concurrent map）维护 `operationId → operationSpan`、`operationId+turnIndex → turnSpan`、`operationId+turnIndex+toolCallId → toolSpan`。`startTurnSpan` 从 `SpanStore.parent(operationId)` 取父 span，`makeCurrent` 后子 span 自动挂载。

**context 传播**：OTel `Context.current()` 在 `AgentRuntime.executePrompt` 线程内传播。`AgentRuntime` 当前是同步执行（单线程内 turn 循环），所以 `Context` 自动随线程传播；未来 #9 durable scale 引入跨线程时，需在 turn dispatch 时显式 `Context.wrap`。

**为什么不放进 RuntimeEventRecord**：

1. payload 是稳定 event schema，不应被传输协议（OTel context）污染。
2. `traceId` / `spanId` 是观察期数据，不是业务数据；放 payload 会让 `RuntimeStore` 也要持久化它们。
3. OTel Context 已有标准传播机制，不需要 Helm 自己造。

**MDC 关联**（可选）：logging observer 若想输出 `traceId`，可在 `OpenTelemetryRuntimeObserver.onEvent` 入口把 `Span.current().getSpanContext().getTraceId()` 写入 SLF4J MDC，退出时清理。这样日志与 trace 自动对齐，不需要在 `RuntimeEventRecord` 加字段。

#### 3.4.3 容错

- observer 内部任何异常（OTel SDK 不可用、反射失败、payload 缺字段）一律 catch + DEBUG 日志 + 不重抛——对齐 `appendEventSafely` 的"事件持久化不得改变 operation 结果"。
- payload 字段缺失时跳过该 metric，不报错（向后兼容 #2 未完成时的事件流）。

### 3.5 ContentCaptureLevel

放在 `helm-observability-opentelemetry/src/main/java/io/agent/helm/observability/opentelemetry/ContentCaptureLevel.java`（与 logging adapter 无关，仅 OTel adapter 使用；若 logging adapter 未来也要用，再提升到 `helm-core`）：

```java
package io.agent.helm.observability.opentelemetry;

/**
 * Controls how much event/tool content is captured into spans and metric attributes.
 *
 * <p>Content here means prompt text, model response text, tool input/output objects — never
 * credentials, PII, or {@link io.agent.helm.core.event.Redact @Redact}-annotated fields, which are
 * always redacted regardless of capture level.
 *
 * <p>Default is {@link #METADATA_ONLY} per roadmap §2 principle 6 (safe defaults).
 */
public enum ContentCaptureLevel {
    /** Only structural metadata: ids, types, durations, counts, statuses, error codes. No content. */
    METADATA_ONLY,

    /** Metadata plus truncated content (prompt/response/tool io) truncated to {@code 200 chars}. */
    SUMMARY,

    /** Full content as span attributes. Use only in local dev / troubleshooting. */
    FULL;
}
```

**行为**：

| level | span attribute | metric label |
| --- | --- | --- |
| METADATA_ONLY | id / type / duration / status / code / agent / tool / model | 全部 |
| SUMMARY | 上述 + `helm.prompt.summary`（前 200 字符）、`helm.tool.input.summary`、`helm.tool.output.summary` | 全部 |
| FULL | 上述 + `helm.prompt.full`、`helm.tool.input.full`、`helm.tool.output.full` | 全部 |

**关键约束**：

- `@Redact` 注解字段在任何 level 都不输出——脱敏发生在 `EventRedactor`，observer 拿到的 payload 已脱敏。
- content 永远不进 metrics（metrics 只用 label，label 是结构化枚举值，不是 content）。
- content 只在 span attribute 里出现，且 SUMMARY 截断 200 字符。

**配置**：

```java
OpenTelemetryRuntimeObserver observer = new OpenTelemetryRuntimeObserver(
        openTelemetry,
        operationId -> runtime.lookupAgentName(operationId),
        ContentCaptureLevel.METADATA_ONLY);  // 默认值
```

Spring Boot starter 通过 `helm.observability.otel.content-capture=METADATA_ONLY|SUMMARY|FULL` properties 配置（属 #8 实施阶段，#11 API governance 会规定 properties 命名）。

### 3.6 `helm-observability-opentelemetry` 模块

#### 3.6.1 pom 依赖

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent</groupId>
    <artifactId>helm</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-observability-opentelemetry</artifactId>

  <dependencies>
    <!-- Only depends on helm-core SPI; runtime internals are NOT a dependency. -->
    <dependency>
      <groupId>io.agent</groupId>
      <artifactId>helm-core</artifactId>
      <version>${project.version}</version>
    </dependency>

    <!-- OpenTelemetry API + SDK. Adapter-only dependency; never leaks into helm-core. -->
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-api</artifactId>
      <version>1.40.0</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk</artifactId>
      <version>1.40.0</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk-trace</artifactId>
      <version>1.40.0</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk-metrics</artifactId>
      <version>1.40.0</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-logging</artifactId>
      <version>1.40.0</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk-testing</artifactId>
      <version>1.40.0</version>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

**依赖守则验证**：

- `helm-core` 不依赖本模块——本模块只依赖 `helm-core` 的 `RuntimeEventObserver` / `RuntimeEventRecord` / `Redact` / `Redacted`。
- OTel SDK 依赖只在 `helm-observability-opentelemetry`，**不进** core / runtime / engine。
- 应用若不想用 OTel，可以完全不引入本模块——`LoggingRuntimeObserver` 仍工作。

#### 3.6.2 模块内文件结构

```text
helm-observability-opentelemetry/
  src/main/java/io/agent/helm/observability/opentelemetry/
    OpenTelemetryRuntimeObserver.java       # RuntimeEventObserver 实现：metrics + tracing
    ContentCaptureLevel.java                # enum：METADATA_ONLY / SUMMARY / FULL
    SpanStore.java                          # package-private：operationId → span 并发映射
    OpenTelemetryFactory.java               # 便捷构造：从配置创建 OpenTelemetry 实例 + observer
  src/test/java/io/agent/helm/observability/opentelemetry/
    MetricsObserverContractTest.java        # 见 §6
    RedactionContractTest.java              # 见 §6
    OpenTelemetryRuntimeObserverTest.java   # 单测
    InMemoryOtelTest.java                   # 用 sdk-testing 的 InMemoryExporter
```

### 3.7 与 LoggingRuntimeObserver 协同

`AgentRuntime`（或 `RuntimeStore`）注册多个 `RuntimeEventObserver`，事件按顺序 fan-out：

```java
AgentRuntime runtime = AgentRuntime.builder()
        .agent(myAgent)
        .provider(myProvider)
        .store(store)
        .build();

// Application registers observers (in real life via Spring Boot starter or CLI wiring):
runtime.eventBus().addObserver(new LoggingRuntimeObserver());
runtime.eventBus().addObserver(new OpenTelemetryRuntimeObserver(otel, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY));
```

注：`AgentRuntime` 当前未暴露 `eventBus()`，实施时需新增 observer 注册 API（不在本设计文档范围，由 #2/#11 收口）。当前 `RuntimeEventObserver` SPI 已稳定，注册机制是实施细节。

**事件顺序保证**：

- observer 看到的事件顺序与 store 写入顺序一致（`AgentRuntime.appendEvent` 已保证）。
- 多 observer 间 fan-out 顺序不影响——observer 间无状态共享。
- 单 observer 内部对单事件的处理是同步的——`OpenTelemetryRuntimeObserver.onEvent` 不应阻塞过久（OTel SDK 内部异步批量导出）。

## 4. 数据流与时序

### 4.1 事件 → observer → metrics/span

```text
Application code
  → AgentRuntime.dispatch / prompt
    → AgentRuntime.executePrompt (helm-runtime)
      → appendEvent(OPERATION_STARTED, payload)         # EventRedactor.redact 先脱敏
        → RuntimeStore.appendEvent                       # 持久化已脱敏事件
        → for observer in observers:                     # fan-out
            LoggingRuntimeObserver.onEvent     → SLF4J INFO
            OpenTelemetryRuntimeObserver.onEvent → start operation span
      → AgentEngine.run (helm-agent-engine)
        → TurnRunner.run
          → EngineEventListener.onEvent(MODEL_STARTED)  # 由 #2 转换为 RuntimeEventRecord
            → appendEvent(MODEL_STARTED, payload{provider, model})
              → observers fan-out
                OpenTelemetryRuntimeObserver.onEvent → start provider span (child of operation)
          → ModelProvider.stream
          → ModelStreamEvent.Completed(usage)
          → EngineEventListener.onEvent(MODEL_ENDED, duration, usage)
            → appendEvent(MODEL_SUCCEEDED, payload{durationMs, usage, provider, model})
              → observers fan-out
                OpenTelemetryRuntimeObserver.onEvent → end provider span + record token usage metric
        → ToolExecutor.execute
          → EngineEventListener.onEvent(TOOL_STARTED)
            → appendEvent(TOOL_STARTED, payload{toolName, toolCallId, input})
              → EventRedactor.redact 已递归脱敏 @Redact 字段
              → observers fan-out → start tool span
          → Tool.execute
          → EngineEventListener.onEvent(TOOL_ENDED, duration)
            → appendEvent(TOOL_ENDED, payload{toolName, toolCallId, durationMs, output})
              → observers fan-out → end tool span + record tool.duration metric
      → appendEvent(OPERATION_SUCCEEDED, payload{turnCount, usage})
        → observers fan-out → end operation span + record operation.duration metric
```

### 4.2 trace context 传播层级

```text
[ROOT] helm.operation span
  ├─ helm.turn span (turnIndex=0)
  │   ├─ helm.provider.call span (provider=openai, model=gpt-4o)
  │   ├─ helm.tool.call span (tool=lookup_customer)
  │   └─ helm.provider.call span (provider=openai, model=gpt-4o)
  └─ helm.turn span (turnIndex=1)
      └─ helm.provider.call span (provider=openai, model=gpt-4o)
```

**关键属性**：

- traceId 在整个 operation 内唯一，所有 span 共享。
- spanId 每个 span 唯一，父子关系通过 parentSpanId 链接。
- `operationId` 作为 span attribute 暴露，可在 trace UI 反查 operation。
- `turnIndex` / `toolCallId` 作为子 span attribute，便于定位具体哪一 turn / 哪次 tool 调用慢。
- **traceId 不进 RuntimeEventRecord payload**——`traceId` 通过 OTel `Context.current()` 在线程内传播，logging observer 若要输出 traceId，从 `Span.current()` 读取并写 MDC。

### 4.3 失败路径时序

`OPERATION_FAILED` 携带 payload `{errorCode: "MAX_TURNS_EXCEEDED", message: "..."}`：

```text
appendEvent(OPERATION_FAILED, error)
  → EventRedactor.redact   # developerDetails 整体丢弃, message 由 #2 保证 safe
  → RuntimeStore.appendEvent
  → observers fan-out:
    LoggingRuntimeObserver.onEvent → WARN "helm event operation.failed ..."
    OpenTelemetryRuntimeObserver.onEvent:
      → endOperationSpan(StatusCode.ERROR, code="MAX_TURNS_EXCEEDED")
      → record helm.operation.duration metric (status=failure, code=MAX_TURNS_EXCEEDED)
      → record helm.operation.failure counter (code=MAX_TURNS_EXCEEDED, agent=...)
```

## 5. 安全与边界

### 5.1 content 永不进 metrics

metrics label 是结构化枚举值（`agent` / `status` / `code` / `provider` / `model` / `tool`）。content（prompt 文本、tool 输入输出、model 响应文本）**永远不进 metric**——任何 `ContentCaptureLevel` 下都不进。content 只在 span attribute 里出现，且 SUMMARY 截断、FULL 仅 dev 模式。

### 5.2 默认 METADATA_ONLY

`OpenTelemetryRuntimeObserver` 默认构造（无参）使用 `ContentCaptureLevel.METADATA_ONLY`。Spring Boot starter 默认配置也是 `METADATA_ONLY`。要开 FULL 必须显式配置 + 不应在生产环境使用。

### 5.3 `@Redact` 覆盖所有 capture level

`@Redact` 注解字段在 `EventRedactor.redact` 阶段被替换为 `[REDACTED]`，**早于** observer 接收。因此无论 `ContentCaptureLevel` 是 METADATA_ONLY / SUMMARY / FULL，`@Redact` 字段都不会出现在 span attribute 或 metric label 里。

**这保证了**：业务可以放心标注 `@Redact`，不用担心被 OTel adapter 绕过——脱敏发生在 core/runtime 边界，adapter 拿到的是脱敏后的 payload。

### 5.4 developer details 默认不输出

`EventRedactor` 已丢弃 `developerDetails` key（行 19-21）。本组件继承此行为，不重新引入。OTel span attribute 也不包含 `developerDetails`——error 事件只暴露 `errorCode` 与 safe `message`（来自 #2 的 `HelmException.details`）。

### 5.5 OTel 依赖隔离

- `helm-core` 不引入 OTel 依赖（`@Redact` / `Redacted` / `RuntimeEventObserver` 都不依赖 OTel）。
- `helm-runtime` 不引入 OTel 依赖（`EventRedactor` 升级为 public 也不依赖 OTel）。
- `helm-agent-engine` 不引入 OTel 依赖。
- OTel API + SDK 只在 `helm-observability-opentelemetry`。
- 应用不引入本模块时，`LoggingRuntimeObserver` 仍工作，运行时无 OTel 类。

### 5.6 observer 容错

`OpenTelemetryRuntimeObserver.onEvent` 内部 catch 所有 `RuntimeException`，DEBUG 日志后吞掉——对齐 `appendEventSafely` 的"事件持久化不得改变 operation 结果"。OTel SDK 不可用 / exporter 不可达 / payload 解析失败 都不影响 operation。

### 5.7 高基数 label 防护

§3.2 已规定 `operationId` / `toolCallId` / `traceId` 不进 metric label。`agent` / `tool` / `model` / `code` 是有界枚举，不会撑爆 cardinality。文档第 3.2 节末尾已显式声明此约束。

## 6. 测试策略

### 6.1 MetricsObserverContractTest

放在 `helm-observability-opentelemetry/src/test/java/io/agent/helm/observability/opentelemetry/MetricsObserverContractTest.java`，使用 OTel `sdk-testing` 的 `InMemoryMetricReader`：

```java
package io.agent.helm.observability.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsObserverContractTest {

    @Test
    void operationSucceededEmitsDurationHistogramAndNoFailureCounter() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(provider).build();

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "coder-agent", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED.type(), Map.of()));
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED.type(), Map.of("turnCount", 3)));

        List<MetricData> metrics = reader.collectAllMetrics();
        assertThat(metrics).anySatisfy(m ->
                assertThat(m.getName()).isEqualTo("helm.operation.duration"));
        assertThat(metrics).noneSatisfy(m ->
                assertThat(m.getName()).isEqualTo("helm.operation.failure"));
    }

    @Test
    void modelSucceededEmitsTokenUsageCounters() {
        // ... emit MODEL_STARTED then MODEL_SUCCEEDED with usage payload ...
        // assert helm.token.usage.input and helm.token.usage.output counters recorded
    }

    @Test
    void operationFailedEmitsFailureCounterWithCode() {
        // ... emit OPERATION_FAILED with errorCode=MAX_TURNS_EXCEEDED ...
        // assert helm.operation.failure counter incremented with label code=MAX_TURNS_EXCEEDED
    }

    private static RuntimeEventRecord event(String type, Map<String, Object> payload) {
        return new RuntimeEventRecord("evt_test", "op_test", null, 1L, type, payload, Instant.now());
    }
}
```

**契约要点**：

- 每个 metric 指标至少一个测试断言其被触发。
- payload 缺字段时不抛错（向后兼容 #2 未完成时）。
- label 取值与 §3.2 表一致。

### 6.2 RedactionContractTest

放在 `helm-observability-opentelemetry/src/test/java/io/agent/helm/observability/opentelemetry/RedactionContractTest.java`（也可放 `helm-runtime` 测试，因为 `EventRedactor` 在 `helm-runtime`）：

```java
package io.agent.helm.observability.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.Redact;
import io.agent.helm.runtime.EventRedactor;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactionContractTest {

    record CustomerInput(String customerId, @Redact("PII") String phoneNumber, String tier) {}

    @Test
    void annotatedRecordComponentIsRedacted() {
        CustomerInput input = new CustomerInput("c-1", "555-1234", "gold");
        CustomerInput redacted = (CustomerInput) EventRedactor.redactRecord(input);

        assertThat(redacted.customerId()).isEqualTo("c-1");
        assertThat(redacted.phoneNumber()).isEqualTo("[REDACTED]");
        assertThat(redacted.tier()).isEqualTo("gold");
    }

    @Test
    void payloadWithRedactedWrapperReplacesValue() {
        Map<String, Object> payload = Map.of(
                "tool", "lookup_customer",
                "input", new CustomerInput("c-1", "555-1234", "gold"));
        Map<String, Object> redacted = EventRedactor.redact(payload);

        // input record inside payload is redacted recursively via redactRecord
        CustomerInput redactedInput = (CustomerInput) redacted.get("input");
        assertThat(redactedInput.phoneNumber()).isEqualTo("[REDACTED]");
    }

    @Test
    void heuristicKeyNamesStillRedactedAlongsideAnnotation() {
        Map<String, Object> payload = Map.of(
                "authorization", "Bearer abc",
                "phoneNumber", "555-1234");
        Map<String, Object> redacted = EventRedactor.redact(payload);
        assertThat(redacted.get("authorization")).isEqualTo("[REDACTED]");
    }

    @Test
    void developerDetailsDroppedEntirely() {
        Map<String, Object> payload = Map.of(
                "errorCode", "TOOL_EXECUTION_FAILED",
                "developerDetails", Map.of("stackTrace", "..."));
        Map<String, Object> redacted = EventRedactor.redact(payload);
        assertThat(redacted).doesNotContainKey("developerDetails");
    }
}
```

### 6.3 OTel InMemoryExporter 集成测试

```java
@Test
void operationSpanHierarchyLinksTurnToolAndProviderSpans() {
    InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

    OpenTelemetryRuntimeObserver observer =
            new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

    // Replay a synthetic event stream
    observer.onEvent(event("operation.started", Map.of()));
    observer.onEvent(event("turn.started", Map.of("turnIndex", 0)));
    observer.onEvent(event("model.started", Map.of("turnIndex", 0, "modelRef", "openai:gpt-4o")));
    observer.onEvent(event("model.succeeded", Map.of(
            "turnIndex", 0, "durationMs", 320, "provider", "openai", "model", "gpt-4o",
            "usage", Map.of("inputTokens", 120, "outputTokens", 45))));
    observer.onEvent(event("turn.succeeded", Map.of("turnIndex", 0, "durationMs", 350)));
    observer.onEvent(event("operation.succeeded", Map.of("turnCount", 1)));

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertThat(spans).hasSize(3); // operation + turn + provider

    SpanData opSpan = spans.stream().filter(s -> s.getName().equals("helm.operation")).findFirst().orElseThrow();
    SpanData turnSpan = spans.stream().filter(s -> s.getName().equals("helm.turn")).findFirst().orElseThrow();
    SpanData providerSpan = spans.stream().filter(s -> s.getName().equals("helm.provider.call")).findFirst().orElseThrow();

    assertThat(turnSpan.getParentSpanId()).isEqualTo(opSpan.getSpanId());
    assertThat(providerSpan.getParentSpanId()).isEqualTo(turnSpan.getSpanId());
    // All three share the same traceId
    assertThat(turnSpan.getTraceId()).isEqualTo(opSpan.getTraceId());
    assertThat(providerSpan.getTraceId()).isEqualTo(opSpan.getTraceId());
}
```

### 6.4 FakeProvider 端到端测试

复用 `helm-runtime` 的 `FakeProvider`，在 `AgentRuntime` 注册 OTel observer，跑一次完整 prompt，断言：

- `helm.operation.duration` 被记录
- `helm.provider.duration` 被记录（FakeProvider 触发 `MODEL_STARTED/SUCCEEDED`）
- span 层级正确（operation → turn → provider）
- 默认 METADATA_ONLY 下，span attribute 不含 prompt 文本

## 7. 验收标准

对齐 `docs/roadmap.md` M9 验收：

- [ ] **默认日志只记录 metadata/summary**：`LoggingRuntimeObserver` 已满足；`OpenTelemetryRuntimeObserver` 默认 `METADATA_ONLY`，span 不含 content。
- [ ] **developer details 默认不输出**：`EventRedactor` 丢弃 `developerDetails` key；OTel span attribute 不引入 `developerDetails`。
- [ ] **operation/run/model/tool 可通过 ID 关联**：`operationId` / `turnIndex` / `toolCallId` 作为 span attribute；traceId 在同 operation 内共享；可通过 traceId 跨 turn/tool/provider 关联。
- [ ] metrics 指标集（§3.2 全部 8 个）在 `OpenTelemetryRuntimeObserver` 中实现并有契约测试。
- [ ] `@Redact` 注解 + `EventRedactor.redactRecord` 递归脱敏业务 record，有 `RedactionContractTest`。
- [ ] `helm-observability-opentelemetry` 模块 pom 仅依赖 `helm-core` + OTel SDK，core/runtime/engine 无 OTel 依赖。
- [ ] `ContentCaptureLevel` 三档行为有测试覆盖；FULL 模式下 `@Redact` 字段仍被脱敏。
- [ ] observer 容错：OTel SDK 不可用 / payload 缺字段 不影响 operation 结果。

## 8. 风险与未决项

### 8.1 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| record 反射性能（高流量场景） | `EventRedactor.redactRecord` 用反射重建 record，单次约 5-10μs；高 QPS 下累积成本 | 事件流量 < 1k/s 时无影响；超阈值时考虑注解处理器预生成 redactor（未决项 8.3） |
| OTel SDK 启动开销 | 应用引入 OTel SDK 后启动时间增加 50-100ms | 已隔离在 adapter 模块，应用可选引入 |
| payload schema 演进 | #2 engine hardening 调整 payload 字段名（如 `durationMs` 改 `duration_ms`）会破坏 observer | observer 对字段缺失容错（跳过 metric 不抛错）；契约测试锁定字段名 |
| span 在多线程下传播 | 当前 `AgentRuntime` 同步执行无问题；#9 durable scale 引入跨线程 turn dispatch 时，OTel Context 需显式 `Context.wrap` | 当前设计不解决，留待 #9 |
| `EventRedactor` 升级为 public | 改变 package-private → public，外部 adapter 可依赖 | API governance（#11）需把 `EventRedactor` 列为 SPI 兼容类，pre-1.0 兼容策略覆盖 |

### 8.2 未决项

1. **observer 注册机制**：`AgentRuntime` 当前未暴露 `eventBus().addObserver(...)`，本设计假设存在；具体 API 由 #2/#11 收口。
2. **SpanStore 实现细节**：当前 sketch 用 `concurrent map<key, span>`，未处理 operation 完成后清理；实施时需考虑 TTL 或 weak reference。
3. **`@Redact` 注解处理器**：当前用反射，未来若性能不足，可改用注解处理器生成 `Redactor<CustomerInput>` 静态实现。是否引入未决。
4. **MDC traceId 注入**：本设计提到 logging observer 可从 `Span.current()` 读 traceId 写 MDC，但具体实现（在哪个 observer 注入、清理时机）未定。建议在 `OpenTelemetryRuntimeObserver` 的 `onEvent` 入口注入，但需考虑 `LoggingRuntimeObserver` 是否在同一 fan-out 顺序下先执行。
5. **OTel SDK 版本固定**：本设计写死 `1.40.0`，实施时取最新稳定版；是否引入 OTel BOM 由 #10 release engineering 决定。
6. **`helm.turn.count` 数据源**：依赖 #2 在 `OPERATION_SUCCEEDED` payload 写入 `turnCount`；若 #2 改为只在 `TurnEnded` 累加，需 observer 自己数 turn 事件。
7. **rate-limit 拒绝事件来源**：本设计假设 `OPERATION_FAILED` payload 含 `errorCode=RATE_LIMITED`；#7 rate limiting 实施时若用独立事件类型（如新增 `RATE_LIMIT_REJECTED`），observer 需调整。

## 9. 与其他组件的关系

| 组件 | 关系 | 说明 |
| --- | --- | --- |
| #1 streaming API | 被依赖 | streaming 完成后 `MODEL_SUCCEEDED` 携带 usage；本组件消费此事件发 token metric 与 provider span。streaming 不阻塞本组件 |
| #2 engine hardening | **强依赖** | 本组件假设 #2 已完成：`TURN_*` / `MODEL_*` / `TOOL_*` 事件已在 RuntimeEventRecord 流里，payload 携带 `durationMs` / `usage` / `errorCode` / `turnCount` / `modelRef`。#2 未完成时 observer 只能消费 `OPERATION_*`，metrics 子集缩小 |
| #3 json schema extensions | 无直接关系 | tool input/output 校验不影响脱敏——`@Redact` 在校验后脱敏 |
| #4 memory semantic retrieval | 无直接关系 | memory 检索不产生 metric；未来若 memory store 想发指标，可独立注册 RuntimeEventObserver |
| #5 authorizer | 无直接关系 | 授权失败事件若走 `OPERATION_FAILED` + `errorCode=UNAUTHORIZED`，本组件会自动计 failure metric |
| #6 http client sdk | 被依赖 | HTTP client/server interceptor 实现 cross-process trace context propagation（W3C TraceContext header），属 #6 范围；本组件只做 JVM 内传播 |
| #7 rate limiting | 被依赖 | rate-limit 拒绝事件触发 `helm.rate_limit.rejected` counter；事件 schema 由 #7 定义（未决项 8.7） |
| #8 metrics/otel（本文） | — | — |
| #9 durable scale | 被依赖 | 跨线程 / 跨进程 dispatch 时需显式 OTel `Context.wrap`；本组件不解决，留待 #9 |
| #10 release engineering | 横切 | OTel SDK 版本管理、BOM、Maven publishing 归 #10 |
| #11 api governance | **强依赖** | `EventRedactor` 升级为 public 须列入 SPI 兼容类；`@Redact` / `Redacted` 注解是 public API；`ContentCaptureLevel` 是 public enum；properties 命名（`helm.observability.otel.*`）由 #11 规定 |
| logging observer（已存在） | 协同 | 两者都从同一 event 流消费，互不依赖；可选 MDC traceId 注入让日志与 trace 对齐（未决项 8.4） |

**实施顺序建议**：

1. **#11 API governance** 先收口 `EventRedactor` 升级为 public 的兼容性策略。
2. **#2 engine hardening** 完成 turn/model/tool 事件发射 + payload 字段稳定。
3. **#8 本组件** 实施 `@Redact` 注解 + `EventRedactor.redactRecord`（可独立于 OTel adapter 完成，先让 `LoggingRuntimeObserver` 受益）。
4. **#8 本组件** 实施 `helm-observability-opentelemetry` 模块。
5. **#7 rate limiting** 完成后调整 `helm.rate_limit.rejected` 触发点。
6. **#9 durable scale** 完成时补 OTel Context 跨线程传播。
