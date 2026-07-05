# 7. Rate Limiting / Admission

Helm 在系统设计 Milestone 1–5 之后已具备完整 MVP 基座（core/engine/runtime/provider/sandbox/HTTP/CLI/Spring Boot starter/JDBC/logging observer/memory/session）。但 `AgentRuntime.prompt` / `dispatch` / `WorkflowRuntime.invoke` 当前同步执行，无并发上限、无配额、无排队，任何调用方都可以无约束地发起请求。本组件为 admission 管道增加 rate limiting 能力：在 operation/run 记录创建前进行配额检查，被拒的请求不进入执行路径，防止单一 principal / agent 耗尽系统资源。

本组件是 `docs/design/README.md` 第 2 节缺口表中第 7 项，对应 `docs/roadmap.md` 第 3.1 节"仍留待后续 milestone 的生产能力：……rate limiting……"。

---

## 1. 背景与目标

### 1.1 为什么需要 rate limiting

Helm 当前的 admission 管道只有两层防护：

1. **session 级并发隔离**：`AgentRuntime.executePrompt` 用 `activeSessions` `ConcurrentHashMap` 保证同一 session 串行执行（`SessionBusyException`）。
2. **operation 记录**：每次 prompt / dispatch 都创建 `OperationRecord(RUNNING)`，执行完成后转为 `SUCCEEDED` / `FAILED`。

这两层都不做配额控制：

- `activeSessions` 是 per-session 锁，不限制跨 session 的总并发量。一个 principal 可以同时打开 1000 个 session，每个 session 各发一个 prompt，全部通过 session 锁检查。
- `OperationRecord` 是事后记录，不阻止请求进入。即使有 10000 个 pending operation，runtime 也会继续接收。
- HTTP 层（`helm-http-servlet`）当前没有 429 响应路径，所有错误都映射到 4xx/5xx，调用方无法区分"业务错误"与"过载"。

在生产环境中，这会导致：

- 单个 principal 的高频调用耗尽 provider 配额（OpenAI / Anthropic 按 token 计费且有 RPM 限制）。
- 单个 agent 的突发流量拖垮整个 runtime（所有 agent 共享同一 JVM）。
- 无法对多租户场景做公平调度（无 per-principal 配额）。
- 调用方不知道何时该重试（无 `Retry-After` 信号）。

### 1.2 本组件目标

| 目标 | 内容 |
| --- | --- |
| 定义 `RateLimiter` SPI | 放 `helm-core` `core.admission` 子包，`tryAcquire(RateLimitKey)` 返回 `AcquisitionResult`，可抛 `RateLimitExceededException`。 |
| 多维度限流 | `RateLimitKey(dimension, value)`，dimension ∈ {PRINCIPAL, AGENT, SESSION, GLOBAL}，可在同一 admission 中组合多个维度（per-principal + per-agent 同时限流）。 |
| 默认内存实现 | `TokenBucketRateLimiter`（token bucket 算法，per-key capacity + refillRate），放 `helm-runtime`。 |
| Admission 插入点 | `AgentRuntime.executePrompt` 在生成 operationId 后、`saveOperation(RUNNING)` 前调用 `tryAcquire`；`WorkflowRuntime.invoke` 同理。被拒不创建 operation 记录。 |
| 结构化错误 | `RateLimitExceededException extends HelmException`，code=`RATE_LIMITED`，details 含 retryAfterMs / dimension / limit，安全可暴露。 |
| HTTP 429 集成 | `helm-http-core` 错误映射将 `RATE_LIMITED` 映射为 429 + `Retry-After` header。 |
| 默认无限制 | dev 友好：未配置 `RateLimiter` 时等同于无限制，启动时打 WARN 日志。 |
| 合约测试 | `RateLimiterContractTest` 抽象基类（helm-core test-jar），覆盖超限拒绝、桶恢复、多维度独立、并发安全。 |

### 1.3 不解决什么

- **不做 durable 排队**：被拒请求直接抛 `RateLimitExceededException`，不进入排队等待。durable queue / async workers 归第 9 组件（M11）。
- **不做并发上限控制**：本组件只做时间窗口配额（RPM/RPS），不做 max in-flight 并发控制。max in-flight 与 per-session FIFO queue 归第 9 组件。
- **不做请求优先级**：所有请求同等优先级，无抢占、无权重排队。
- **不做分布式限流**：默认实现是单机内存 token bucket，不跨 JVM 共享状态。分布式限流（Redis-backed 等）留作未来 adapter。
- **不做 authorizer 实现**：本组件只定义 rate limiter SPI 与插入点；authorizer（第 5 组件）是独立 SPI，二者通过 admission 顺序协作。

---

## 2. 现状与缺口

### 2.1 roadmap 出处

| 出处 | 内容 |
| --- | --- |
| `docs/roadmap.md` 第 2 节原则 4 | "Admission 优先：prompt、dispatch、workflow invoke 都应先形成可检查的 operation/run 记录，再执行或排队。" 当前 admission 只做记录，不做配额。 |
| `docs/roadmap.md` 第 3.1 节 | "仍留待后续 milestone 的生产能力：流式响应 API 暴露、并发/队列调度（M11）、**rate limiting**、authorizer 落地（M6）、metrics/OTel（M9）、remote sandbox（M11）……" 明确 rate limiting 为留待后续项。 |
| `docs/roadmap.md` M6 段（第 5 节） | HTTP core 交付项含"request body size/depth/timeouts"，但无 rate limit / 429 路径。 |
| `docs/roadmap.md` M11 段（第 5 节） | "async workers / queue-backed admission"、"per-session FIFO queue"——M11 的 durable queue 在 admission 之后，本组件的 rate limit 在 admission 之内，二者是前置关系。 |

### 2.2 代码现状

#### 2.2.1 AgentRuntime.executePrompt

文件：`helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java`

当前 admission 顺序（行号对应源文件）：

| 行 | 代码 | 作用 |
| --- | --- | --- |
| 79 | `String operationId = "op_" + UUID.randomUUID();`（在 `prompt()` 内） | 生成 operation id |
| 128 | `String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());` | 计算 session id |
| 129-131 | `if (activeSessions.putIfAbsent(sessionId, TRUE) != null) throw SessionBusyException` | session 级并发隔离 |
| 132 | `Instant now = Instant.now();` | 时间戳 |
| 135-144 | `store.saveOperation(new OperationRecord(operationId, sessionId, "PROMPT", RUNNING, ...))` | 创建 RUNNING operation 记录 |
| 145-150 | `appendEvent(OPERATION_STARTED, ...)` | 记录开始事件 |
| 152+ | `agent(...)` → `provider.resolve(...)` → `engine.run(...)` | 执行 |
| 206-215 | `store.saveOperation(... SUCCEEDED ...)` | 记录成功 |
| 223-236 | catch: `store.saveOperation(... FAILED ...)` + `throw e` | 记录失败 |
| 238 | `activeSessions.remove(sessionId)` | 释放 session 锁 |

**缺口**：在第 128 行（sessionId 计算）与第 135 行（saveOperation）之间，没有任何 per-principal / per-agent / global 配额检查。请求一旦通过 session 锁就直接创建 operation 并执行。

#### 2.2.2 WorkflowRuntime.invoke

文件：`helm-runtime/src/main/java/io/agent/helm/runtime/WorkflowRuntime.java`

当前 admission 顺序：

| 行 | 代码 | 作用 |
| --- | --- | --- |
| 53 | `String runId = "run_" + UUID.randomUUID();` | 生成 run id |
| 54 | `Instant now = Instant.now();` | 时间戳 |
| 55-56 | `store.saveWorkflowRun(new WorkflowRunRecord(runId, ..., RUNNING, ...))` | 创建 RUNNING run 记录 |
| 58-63 | `appendEvent(WORKFLOW_STARTED, ...)` | 记录开始事件 |
| 64+ | `workflow.config()` → build agentRuntime → `workflow.run(...)` | 执行 |
| 84-92 | `store.saveWorkflowRun(... SUCCEEDED ...)` | 记录成功 |
| 99-112 | catch: `store.saveWorkflowRun(... FAILED ...)` + `throw IllegalStateException` | 记录失败 |

**缺口**：WorkflowRuntime 没有 session 锁（workflow 是无状态有限任务），更没有任何配额检查。`invoke` 在第 53 行生成 runId 后立即创建 RUNNING 记录并执行。

#### 2.2.3 OperationStatus

文件：`helm-core/src/main/java/io/agent/helm/core/store/OperationStatus.java`

```java
public enum OperationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
```

当前只有三种状态。rate limit 拒绝是否需要新增 `REJECTED` 状态？见 §3.7 决策。

### 2.3 缺口总结

| 缺口 | 影响 | 本组件解决 |
| --- | --- | --- |
| 无 `RateLimiter` SPI | 无法接入任何配额策略（内存/Redis/外部 service） | §3.4 |
| 无 per-principal / per-agent / global 配额 | 单一调用方可耗尽资源 | §3.2 + §3.7 |
| admission 管道无配额检查点 | 请求直接进入执行路径 | §3.7 |
| 无 `RateLimitExceededException` | 调用方无法区分过载与业务错误 | §3.5 |
| HTTP 无 429 路径 | 调用方无 `Retry-After` 信号 | §3.8 |
| 无合约测试 | 限流实现正确性无法保证 | §6 |

---

## 3. 设计方案

### 3.1 模块归属

| 类型 | 模块 | 包 | 说明 |
| --- | --- | --- | --- |
| SPI | helm-core | `io.agent.helm.core.admission` | `RateLimiter`、`RateLimitKey`、`RateLimitDimension`、`AcquisitionResult` |
| 异常 | helm-core | `io.agent.helm.core.error` | `RateLimitExceededException`（与现有 `SessionBusyException` 同包） |
| 内存实现 | helm-runtime | `io.agent.helm.runtime.admission` | `TokenBucketRateLimiter`（package 内部可见，通过 `Builder.rateLimiter()` 注入） |
| 合约测试 | helm-core test-jar | `io.agent.helm.core.admission`（test） | `RateLimiterContractTest` 抽象基类 |
| HTTP 集成 | helm-http-core | `io.agent.helm.http` | 错误映射增加 429 + `Retry-After` |

依赖守则：`helm-core` 的 `core.admission` 包不依赖 runtime/engine/HTTP/任何 adapter。`TokenBucketRateLimiter` 在 `helm-runtime` 中实现，只依赖 `helm-core` SPI。

### 3.2 RateLimitKey 与 RateLimitDimension

```java
package io.agent.helm.core.admission;

/**
 * 限流维度。每个维度对应一种\\\"谁来消耗配额\\\"的视角。
 *
 * <p>维度选择原则：
 * <ul>
 *   <li>PRINCIPAL — 防止单个调用方（用户/租户/API key）耗尽全局资源。</li>
 *   <li>AGENT — 防止单个 agent 定义被高频调用拖垮共享 runtime。</li>
 *   <li>SESSION — per-session 配额（与 {@code SessionBusyException} 的并发隔离正交：后者保证串行，前者限制总速率）。</li>
 *   <li>GLOBAL — 系统级总配额上限，保护下游 provider 与自身 JVM。</li>
 * </ul>
 */
public enum RateLimitDimension {
    PRINCIPAL,
    AGENT,
    SESSION,
    GLOBAL
}
```

```java
package io.agent.helm.core.admission;

import java.util.Objects;

/**
 * 限流键：维度 + 值。同一个请求通常组合多个 key 同时检查（如 per-principal + per-agent + global）。
 *
 * <p>{@code value} 是该维度下的标识：
 * <ul>
 *   <li>PRINCIPAL — principal id（来自 SecurityContext，见第 5 组件）</li>
 *   <li>AGENT — agent name（{@code AgentDefinition.name()}）</li>
 *   <li>SESSION — session id（{@code agentName:instanceId:sessionName}）</li>
 *   <li>GLOBAL — 固定常量 {@code \\\"global\\\"}（所有 GLOBAL key 共享一个桶）</li>
 * </ul>
 *
 * @param dimension 限流维度
 * @param value 维度内的标识
 */
public record RateLimitKey(RateLimitDimension dimension, String value) {
    public RateLimitKey {
        Objects.requireNonNull(dimension, "dimension");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    /** GLOBAL 维度的便捷工厂：所有 GLOBAL 限流共享同一 key。 */
    public static RateLimitKey global() {
        return new RateLimitKey(RateLimitDimension.GLOBAL, "global");
    }

    public static RateLimitKey principal(String principalId) {
        return new RateLimitKey(RateLimitDimension.PRINCIPAL, principalId);
    }

    public static RateLimitKey agent(String agentName) {
        return new RateLimitKey(RateLimitDimension.AGENT, agentName);
    }

    public static RateLimitKey session(String sessionId) {
        return new RateLimitKey(RateLimitDimension.SESSION, sessionId);
    }
}
```

### 3.3 AcquisitionResult

```java
package io.agent.helm.core.admission;

/**
 * tryAcquire 的返回值。无论 allowed 为 true 或 false 都返回此 record，
 * 由调用方决定是继续执行还是抛 {@link RateLimitExceededException}。
 *
 * @param allowed 是否获准
 * @param retryAfterMs 建议的重试等待毫秒数；allowed=true 时为 0，allowed=false 时为预计的等待时间
 * @param remaining 获准后该维度剩余可用配额；allowed=false 时为 0。可用于 metrics / 日志
 */
public record AcquisitionResult(boolean allowed, long retryAfterMs, long remaining) {

    /** 快捷工厂：获准，无等待信息。 */
    public static AcquisitionResult allowed(long remaining) {
        return new AcquisitionResult(true, 0L, remaining);
    }

    /** 快捷工厂：被拒，给出建议重试等待。 */
    public static AcquisitionResult denied(long retryAfterMs) {
        return new AcquisitionResult(false, Math.max(0L, retryAfterMs), 0L);
    }
}
```

设计说明：

- `tryAcquire` 不直接抛异常，而是返回 `AcquisitionResult`。原因：调用方（`AgentRuntime`）需要在多维度检查中决定何时抛、抛什么 dimension 的异常（见 §4.3）。如果 `tryAcquire` 直接抛，无法在多维度场景中做 partial-failure refund。
- `remaining` 字段供 metrics / 日志使用，不暴露给 HTTP 响应（避免泄漏内部容量）。
- `retryAfterMs` 在被拒时由 token bucket 根据当前缺令牌数与 refillRate 计算（见 §3.6）。

### 3.4 RateLimiter SPI

```java
package io.agent.helm.core.admission;

/**
 * 限流器 SPI。实现方决定算法（token bucket / sliding window / 外部服务），
 * 调用方（{@code AgentRuntime} / {@code WorkflowRuntime}）在 admission 管道中调用。
 *
 * <p>线程安全要求：实现必须支持多线程并发调用 {@link #tryAcquire} 与 {@link #release}。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@code tryAcquire} 消耗一个令牌（若可用）。对 token bucket 而言令牌在时间窗口内不返回。</li>
 *   <li>{@code release} 归还一个先前消耗的令牌，仅用于多维度检查的 partial-failure refund。
 *       正常执行完成后<b>不</b>调用 release（rate limiting 不是 concurrency limiting）。</li>
 *   <li>{@code release} 对未持有令牌的 key 调用应为 no-op（不抛异常），以容忍降级路径。</li>
 * </ul>
 *
 * <p>默认实现 {@code TokenBucketRateLimiter} 见 §3.6。
 */
public interface RateLimiter {

    /**
     * 尝试为指定 key 消耗一个配额令牌。
     *
     * @param key 限流键（维度 + 值）
     * @return 获取结果；{@link AcquisitionResult#allowed()} 为 false 表示被拒
     */
    AcquisitionResult tryAcquire(RateLimitKey key);

    /**
     * 归还一个先前消耗的令牌。仅用于多维度检查中后续维度失败时回滚先前已消耗的令牌。
     *
     * <p>正常执行完成后不调用此方法。此方法不用于 concurrency limiting。
     *
     * @param key 先前 tryAcquire 成功的 key
     */
    void release(RateLimitKey key);
}
```

为什么 SPI 包含 `release`：

- **多维度 partial-failure refund**：当请求需要同时检查 GLOBAL + PRINCIPAL + AGENT 三个维度时，如果 GLOBAL 与 PRINCIPAL 通过但 AGENT 被拒，需要把已消耗的 GLOBAL 与 PRINCIPAL 令牌归还，否则会低估可用配额。
- **不用于执行后释放**：rate limiting 的语义是"时间窗口内的请求配额"，执行完成后令牌不返回（与 concurrency limiting 不同）。文档与合约测试明确这一点。
- **未来扩展**：第 9 组件（durable scale）可能引入 concurrency limiter，复用同一 SPI 但在执行后调用 release。本组件不实现该用法。

### 3.5 RateLimitExceededException

```java
package io.agent.helm.core.error;

import io.agent.helm.core.admission.RateLimitDimension;
import java.util.Map;

/**
 * 限流拒绝异常。admission 管道在 {@code RateLimiter.tryAcquire} 返回 {@code allowed=false} 时抛出。
 *
 * <p>details 字段安全可暴露（会出现在 HTTP 错误响应与 events 中）：
 * <ul>
 *   <li>{@code dimension} — 被拒的限流维度</li>
 *   <li>{@code retryAfterMs} — 建议重试等待毫秒数</li>
 *   <li>{@code limit} — 该维度的配额上限（不暴露剩余量，避免泄漏内部容量）</li>
 * </ul>
 *
 * <p>developerDetails 可包含实现细节（如桶当前水位、refillRate），不进 safe errors。
 */
public final class RateLimitExceededException extends HelmException {

    public RateLimitExceededException(
            String message,
            RateLimitDimension dimension,
            long retryAfterMs,
            long limit,
            Map<String, Object> developerDetails) {
        super(
                "RATE_LIMITED",
                message,
                Map.of(
                        "dimension", dimension.name(),
                        "retryAfterMs", retryAfterMs,
                        "limit", limit),
                developerDetails);
    }
}
```

设计要点：

- code = `RATE_LIMITED`，遵循 `SCREAMING_SNAKE_CASE` 约定（与 `SESSION_BUSY`、`AGENT_NOT_FOUND` 风格一致，不加 `_ERROR` / `_FAILED` 后缀）。
- `details` 只含 dimension / retryAfterMs / limit，**不含** remaining / currentTokens / refillRate 等内部状态，避免泄漏内部容量信息。
- `limit` 字段的存在让调用方知道被拒维度的总配额，便于客户端做自适应限速。
- `developerDetails` 由实现填充（如 token bucket 当前水位），只进日志不进 HTTP 响应。

### 3.6 TokenBucketRateLimiter（内存实现）

放 `helm-runtime` `io.agent.helm.runtime.admission` 包。

#### 3.6.1 算法选择

| 算法 | 优点 | 缺点 | 决策 |
| --- | --- | --- | --- |
| **Token bucket** | 允许突发（桶满时可瞬间消耗 capacity 个令牌）；实现简单；内存开销低。 | 突发可能瞬时压垮下游。 | **采用**：突发容忍符合 agent 场景（用户连续对话是正常的）。 |
| Fixed window | 实现最简单。 | 窗口边界双倍流量（窗口切换瞬间可消耗 2× capacity）。 | 不采用。 |
| Sliding window | 流量最平滑。 | 实现复杂（需维护时间戳列表或加权计数）。 | 不采用，留作未来 adapter 选项。 |

token bucket 的突发容忍是刻意选择：agent 对话场景中，用户连续追问 3-4 个问题是正常的，不应被 per-second 严格限流阻断。capacity 设为略高于稳态速率即可吸收合理突发。

#### 3.6.2 实现

```java
package io.agent.helm.runtime.admission;

import io.agent.helm.core.admission.AcquisitionResult;
import io.agent.helm.core.admission.RateLimitDimension;
import io.agent.helm.core.admission.RateLimitKey;
import io.agent.helm.core.admission.RateLimiter;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 token bucket 限流器。每个 {@link RateLimitKey} 维护独立的桶（capacity + refillRate）。
 *
 * <p>桶配置按 dimension 统一设定：同一 dimension 的所有 key 共享 capacity / refillRate 配置，
 * 但每个 key 的 value 不同则桶独立。例如 per-principal 配置为 20/min 时，每个 principal 各有 20/min 的桶。
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} + {@link Bucket} 内部 synchronized 块。
 * Bucket 数量随 key 增长（无淘汰），适用于 principal / agent 数量有限的场景。
 * 若 key 空间无限增长（如 per-session），建议外层包装 LRU 淘汰（本实现不内置）。
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentMap<RateLimitKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<RateLimitDimension, BucketConfig> configs;

    private TokenBucketRateLimiter(Map<RateLimitDimension, BucketConfig> configs) {
        this.configs = Map.copyOf(configs);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AcquisitionResult tryAcquire(RateLimitKey key) {
        BucketConfig config = configs.get(key.dimension());
        if (config == null) {
            // 该维度未配置限流，直接放行
            return AcquisitionResult.allowed(Long.MAX_VALUE);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(config));
        return bucket.tryAcquire();
    }

    @Override
    public void release(RateLimitKey key) {
        Bucket bucket = buckets.get(key);
        if (bucket != null) {
            bucket.release();
        }
    }

    /** 单个桶的配置：容量 + 补充速率。 */
    private record BucketConfig(long capacity, Duration refillInterval) {
        BucketConfig {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            Objects.requireNonNull(refillInterval, "refillInterval");
            if (refillInterval.isZero() || refillInterval.isNegative()) {
                throw new IllegalArgumentException("refillInterval must be positive");
            }
        }

        /** 每次补充的令牌数（整数）= 1，补充间隔 = refillInterval。 */
        long refillRatePerMs() {
            return 1_000_000L / refillInterval.toMillis(); // nanos per token 的倒数
        }
    }

    /** 单个 key 的桶状态：当前令牌数 + 上次补充时间。 */
    private static final class Bucket {
        private final BucketConfig config;
        private double tokens;
        private long lastRefillNanos;

        private Bucket(BucketConfig config) {
            this.config = config;
            this.tokens = config.capacity(); // 桶初始满
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized AcquisitionResult tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return AcquisitionResult.allowed((long) tokens);
            }
            // 计算还需等待多久才能补充 1 个令牌
            double deficit = 1.0 - tokens;
            long retryAfterMs = (long) Math.ceil(deficit * config.refillInterval().toMillis());
            return AcquisitionResult.denied(retryAfterMs);
        }

        private synchronized void release() {
            refill();
            if (tokens < config.capacity()) {
                tokens = Math.min(config.capacity(), tokens + 1.0);
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            // 按比例补充：elapsedNanos / refillInterval.toNanos() 个令牌
            double refilled = (double) elapsedNanos / config.refillInterval().toNanos();
            tokens = Math.min(config.capacity(), tokens + refilled);
            lastRefillNanos = now;
        }
    }

    public static final class Builder {
        private final Map<RateLimitDimension, BucketConfig> configs = new java.util.HashMap<>();

        /**
         * 配置某维度的限流：每 {@code refillInterval} 补充 1 个令牌，桶容量 {@code capacity}。
         *
         * @param dimension 限流维度
         * @param capacity 桶容量（允许的最大突发请求数）
         * @param refillInterval 补充 1 个令牌的时间间隔
         */
        public Builder limit(RateLimitDimension dimension, long capacity, Duration refillInterval) {
            configs.put(dimension, new BucketConfig(capacity, refillInterval));
            return this;
        }

        public TokenBucketRateLimiter build() {
            if (configs.isEmpty()) {
                throw new IllegalStateException("at least one dimension limit must be configured");
            }
            return new TokenBucketRateLimiter(configs);
        }
    }
}
```

使用示例：

```java
RateLimiter limiter = TokenBucketRateLimiter.builder()
        .limit(RateLimitDimension.GLOBAL,    100, Duration.ofSeconds(1))   // 100 RPS 全局
        .limit(RateLimitDimension.PRINCIPAL,  20, Duration.ofSeconds(1))   // 20 RPS per principal
        .limit(RateLimitDimension.AGENT,       50, Duration.ofSeconds(1))   // 50 RPS per agent
        .build();

AgentRuntime runtime = AgentRuntime.builder()
        .agent(codingAgent)
        .provider(provider)
        .store(store)
        .rateLimiter(limiter)
        .build();
```

### 3.7 Admission 插入点

#### 3.7.1 AgentRuntime.executePrompt 修改

在 `executePrompt` 中，session 锁检查之后、`saveOperation(RUNNING)` 之前插入 rate limit 检查：

```java
private PromptExecution executePrompt(AgentPromptRequest request, String operationId) {
    String sessionId = sessionId(request.agentName(), request.sessionName(), request.sessionName());
    if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
        throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
    }
    Instant now = Instant.now();

    // ===== 新增：rate limit admission =====
    RateLimitSnapshot rl = null;
    if (rateLimiter != null) {
        rl = acquireRateLimit(request, sessionId); // 内部做多维度检查，失败抛 RateLimitExceededException
    }
    // =======================================

    try {
        store.saveOperation(new OperationRecord(
                operationId, sessionId, "PROMPT", OperationStatus.RUNNING, ...));
        appendEvent(operationId, null, 1, RuntimeEventType.OPERATION_STARTED, ...);
        // ... 执行逻辑不变 ...
        return new PromptExecution(operationId, result.text());
    } catch (RuntimeException e) {
        // ... 失败记录逻辑不变 ...
        throw e;
    } finally {
        // 注意：rate limit 令牌在成功路径上不释放（rate limiting 语义）
        // 仅在 admission 阶段因后续维度失败时由 acquireRateLimit 内部 refund
        activeSessions.remove(sessionId);
    }
}
```

**决策：被拒请求不创建 OperationRecord。** 理由：

1. **admission 语义**：rate limit 拒绝发生在 operation 创建之前。被拒请求没有进入执行路径，不构成一个 operation。创建 `REJECTED` 记录会让 `listOperations()` 混入大量被拒请求，干扰真实 operation 的查询。
2. **OperationStatus 保持简洁**：当前 `RUNNING / SUCCEEDED / FAILED` 三状态足以描述执行生命周期。新增 `REJECTED` 会让状态机复杂化（REJECTED 不是 FAILED，也不是 terminal 执行状态）。
3. **可观测性通过其他通道**：rate limit 拒绝的观测通过 HTTP 429 响应、metrics（第 8 组件）、logging observer 完成，不需要 operation 记录。
4. **与 dispatch 的关系**：`dispatch()` 在 catch 中调用 `store.loadOperation(operationId)` 来判断状态。如果被拒时不创建 operation，`dispatch` 的 catch 路径会走到 `OperationStatus.FAILED` 默认值——这是合理的，因为 `RateLimitExceededException` 是 RuntimeException，`dispatch` 会捕获它。但更准确的做法是让 `dispatch` 在 admission 失败时不调用 `executePrompt` 的 catch 路径。见 §3.7.3。

#### 3.7.2 WorkflowRuntime.invoke 修改

```java
public <I, O> WorkflowRunHandle<O> invoke(WorkflowInvokeRequest<I> request) {
    WorkflowDefinition<I, O> workflow = (WorkflowDefinition<I, O>) workflows.get(request.workflowName());
    if (workflow == null) {
        throw new WorkflowNotFoundException(...);
    }

    String runId = "run_" + UUID.randomUUID();
    Instant now = Instant.now();

    // ===== 新增：rate limit admission =====
    if (rateLimiter != null) {
        acquireWorkflowRateLimit(request); // 失败抛 RateLimitExceededException，不创建 run 记录
    }
    // =======================================

    store.saveWorkflowRun(new WorkflowRunRecord(runId, ..., WorkflowRunStatus.RUNNING, ...));
    // ... 后续不变 ...
}
```

WorkflowRuntime 当前没有 session 锁（workflow 无状态），rate limit 是唯一的 admission 防护。被拒不创建 `WorkflowRunRecord`，理由同 §3.7.1。

#### 3.7.3 dispatch 的 admission 路径

当前 `dispatch` 在 catch 中通过 `store.loadOperation` 判断状态。引入 rate limit 后：

```java
public OperationHandle dispatch(AgentPromptRequest request) {
    String operationId = "op_" + UUID.randomUUID();
    try {
        executePrompt(request, operationId);
    } catch (RateLimitExceededException e) {
        // 被拒：不创建 operation，直接返回 REJECTED 状态的 handle（不持久化）
        return new OperationHandle(operationId, OperationStatus.FAILED);
    } catch (RuntimeException e) {
        OperationStatus status = store.loadOperation(operationId)
                .map(OperationRecord::status)
                .orElse(OperationStatus.FAILED);
        return new OperationHandle(operationId, status);
    }
    return new OperationHandle(operationId, OperationStatus.SUCCEEDED);
}
```

`dispatch` 捕获 `RateLimitExceededException` 并返回 `FAILED` handle，调用方可通过 `getOperation(operationId)` 发现该 operation 不存在（Optional.empty），从而知道是 admission 拒绝。也可通过 exception 透传（如果调用方需要区分）。

### 3.8 HTTP 429 集成

`helm-http-core` 的错误映射增加 `RATE_LIMITED` → 429 路径：

```java
// 在 HttpErrorMapper 中
static HttpResponse mapError(HelmException e) {
    return switch (e.code()) {
        case "SESSION_BUSY" -> new HttpResponse(409, toJson(e));
        case "RATE_LIMITED" -> {
            long retryAfterMs = (long) e.details().get("retryAfterMs");
            long retryAfterSeconds = Math.max(1, (retryAfterMs + 999) / 1000); // 向上取整
            yield new HttpResponse(
                    429,
                    toJson(e),
                    Map.of("Retry-After", String.valueOf(retryAfterSeconds)));
        }
        default -> new HttpResponse(500, toJson(e));
    };
}
```

HTTP 响应体示例：

```json
HTTP/1.1 429 Too Many Requests
Retry-After: 2
Content-Type: application/json

{
  "code": "RATE_LIMITED",
  "message": "Rate limit exceeded for dimension PRINCIPAL",
  "details": {
    "dimension": "PRINCIPAL",
    "retryAfterMs": 1500,
    "limit": 20
  }
}
```

`Retry-After` header 按 HTTP 规范使用秒（整数），由 `retryAfterMs` 向上取整得到。response body 保留毫秒精度供 SDK 使用。

### 3.9 配置

#### 3.9.1 AgentRuntime.Builder

```java
public static final class Builder {
    // ... 现有字段 ...
    private RateLimiter rateLimiter; // 默认 null = 无限制

    /**
     * 配置 admission 限流器。未配置时 admission 不做配额检查（dev 友好）。
     * 生产环境应配置 {@link TokenBucketRateLimiter} 或自定义实现。
     */
    public Builder rateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        return this;
    }

    public AgentRuntime build() {
        if (rateLimiter == null) {
            // dev 模式：WARN 日志提示未配置限流
            System.Logger.getLogger("io.agent.helm.runtime")
                    .log(System.Logger.Level.WARNING,
                            "AgentRuntime built without RateLimiter; admission will not enforce any quota. "
                                    + "Configure one in production via Builder.rateLimiter(...).");
        }
        return new AgentRuntime(agents, providers, store, memoryStore, maxSessionMessages, rateLimiter);
    }
}
```

#### 3.9.2 WorkflowRuntime.Builder

同模式增加 `rateLimiter(RateLimiter)` 方法。

#### 3.9.3 默认策略

| 场景 | 默认 | 原因 |
| --- | --- | --- |
| dev / 测试 | 不配置 RateLimiter（null） | 开发体验优先，避免测试因限流失败 |
| 生产 | 配置 `TokenBucketRateLimiter`，至少 GLOBAL + PRINCIPAL | 防止单点耗尽全局资源 |
| Spring Boot starter | 通过 `helm.rate-limiter.*` properties 自动配置 | 见第 8 组件（Spring Boot starter）文档 |

---

## 4. 数据流与时序

### 4.1 成功路径时序

```text
调用方
  │
  ▼
AgentRuntime.prompt(request)
  │
  ├─ operationId = "op_" + UUID
  │
  ▼
executePrompt(request, operationId)
  │
  ├─ sessionId = sessionId(agentName, instanceId, sessionName)
  ├─ activeSessions.putIfAbsent(sessionId, TRUE)   ── 失败 ──▶ SessionBusyException
  │
  ├─ [新增] acquireRateLimit(request, sessionId)
  │    ├─ tryAcquire(GLOBAL key)      ── allowed=true  ── remaining=99
  │    ├─ tryAcquire(PRINCIPAL key)   ── allowed=true  ── remaining=19
  │    └─ tryAcquire(AGENT key)       ── allowed=true  ── remaining=49
  │
  ├─ saveOperation(OperationRecord(RUNNING))           ◄── admission 完成，开始执行
  ├─ appendEvent(OPERATION_STARTED)
  │
  ├─ agent(...) → provider.resolve(...) → engine.run(...)
  │    │
  │    ├─ TurnRunner → ModelProvider.stream
  │    └─ ToolCallOrchestrator → Tool
  │
  ├─ saveOperation(OperationRecord(SUCCEEDED))
  ├─ appendEvent(OPERATION_SUCCEEDED)
  │
  └─ finally: activeSessions.remove(sessionId)
       │
       ▼
     返回 PromptResult(operationId, text)
```

### 4.2 拒绝路径时序

```text
调用方
  │
  ▼
AgentRuntime.prompt(request)
  │
  ├─ operationId = "op_" + UUID
  ▼
executePrompt(request, operationId)
  │
  ├─ sessionId = ...
  ├─ activeSessions.putIfAbsent(sessionId, TRUE)  ── 通过
  │
  ├─ [新增] acquireRateLimit(request, sessionId)
  │    ├─ tryAcquire(GLOBAL key)     ── allowed=true   ── 消耗 GLOBAL 令牌
  │    ├─ tryAcquire(PRINCIPAL key)  ── allowed=true   ── 消耗 PRINCIPAL 令牌
  │    └─ tryAcquire(AGENT key)     ── allowed=false  ── retryAfterMs=800
  │         │
  │         ├─ release(PRINCIPAL key)   ◄── partial-failure refund
  │         ├─ release(GLOBAL key)      ◄── partial-failure refund
  │         │
  │         └─ throw RateLimitExceededException(
  │                "Rate limit exceeded for dimension AGENT",
  │                AGENT, retryAfterMs=800, limit=50,
  │                developerDetails={currentTokens=0.0, refillInterval=PT1S})
  │
  ├─ ✗ 不执行 saveOperation（无 RUNNING 记录）
  ├─ ✗ 不执行 appendEvent（无 OPERATION_STARTED）
  │
  └─ finally: activeSessions.remove(sessionId)
       │
       ▼
     RateLimitExceededException 透传给调用方
       │
       ▼
     HTTP 层：429 + Retry-After: 1 + error body
```

### 4.3 多维度检查与 partial-failure refund

`acquireRateLimit` 内部逻辑：

```java
private RateLimitSnapshot acquireRateLimit(AgentPromptRequest request, String sessionId) {
    // 检查顺序：GLOBAL → PRINCIPAL → AGENT（从宽到窄，先保护全局再保护个体）
    List<RateLimitKey> keys = new ArrayList<>();
    keys.add(RateLimitKey.global());
    if (request.principalId() != null) {           // principalId 来自 SecurityContext（第 5 组件）
        keys.add(RateLimitKey.principal(request.principalId()));
    }
    keys.add(RateLimitKey.agent(request.agentName()));
    // SESSION 维度可选：如果需要 per-session 速率限制
    // keys.add(RateLimitKey.session(sessionId));

    List<RateLimitKey> acquired = new ArrayList<>();
    for (RateLimitKey key : keys) {
        AcquisitionResult result = rateLimiter.tryAcquire(key);
        if (!result.allowed()) {
            // refund 已消耗的令牌
            for (RateLimitKey acquiredKey : acquired) {
                rateLimiter.release(acquiredKey);
            }
            throw new RateLimitExceededException(
                    "Rate limit exceeded for dimension " + key.dimension(),
                    key.dimension(),
                    result.retryAfterMs(),
                    // limit 从实现侧获取（需 SPI 扩展或异常内携带，见 §8 未决项）
                    0L,
                    Map.of("key", key.value(), "remaining", result.remaining()));
        }
        acquired.add(key);
    }
    return new RateLimitSnapshot(acquired);
}
```

检查顺序选择 GLOBAL → PRINCIPAL → AGENT 的原因：

1. GLOBAL 是最宽的配额，如果全局已满，无需检查更窄维度。
2. PRINCIPAL 在 AGENT 之前：一个 principal 可能调用多个 agent，先确认 principal 配额再检查具体 agent。
3. 被拒时只 refund 先前已消耗的维度，未检查的维度无需 refund。

### 4.4 与 SessionBusyException 的关系

| 特性 | SessionBusyException | RateLimitExceededException |
| --- | --- | --- |
| 检查对象 | per-session 并发 | per-dimension 速率 |
| 语义 | 同 session 串行执行 | 时间窗口内请求总量 |
| 状态 | `activeSessions` map（瞬时） | token bucket（持续，跨请求） |
| 触发条件 | 同 session 已有在执行 prompt | 某 dimension 配额耗尽 |
| 释放 | prompt 完成（finally remove） | 时间窗口过期（桶补充） |
| HTTP 状态码 | 409 Conflict | 429 Too Many Requests |

二者正交，共存：

- 一个 session 可以同时被 `SessionBusyException`（第二次 prompt 在第一次未完成时）和 `RateLimitExceededException`（该 session 速率超限）保护。
- session 锁在 rate limit 之前获取（先确保串行，再检查配额），在 finally 中释放。
- rate limit 令牌在 admission 阶段消耗，不在 finally 中释放（rate limiting 语义，见 §3.4）。

---

## 5. 安全与边界

### 5.1 per-principal 防滥用

生产环境必须配置 `PRINCIPAL` 维度限流。否则单个 principal 可以通过以下方式耗尽资源：

- 打开大量 session 并发 prompt（每个 session 各发请求，绕过 session 锁）。
- 高频 dispatch（dispatch 是非阻塞的，调用方可快速堆积大量 operation）。
- 调用高成本 agent（如长 context 的 coding agent）耗尽 provider 配额。

`PRINCIPAL` 维度的 `value` 来自 `SecurityContext`（第 5 组件 authorizer）。在 authorizer 未落地前，value 可暂时使用 `instanceId` 或固定占位符 `\\\"anonymous\\\"`，但必须在文档中标注这是临时方案。

### 5.2 retryAfter 不泄漏内部状态

`RateLimitExceededException.details` 中的 `retryAfterMs` 是建议值，由 token bucket 根据当前缺令牌数与 refillInterval 计算。该值：

- 反映的是"还需要多久才能有 1 个令牌"，这是调用方合理需要的信息。
- **不暴露**桶的当前水位（`currentTokens`）、桶容量（`capacity`，已通过 `limit` 字段暴露但这是配置值不是运行状态）、其他 dimension 的状态。
- `developerDetails` 可包含 `currentTokens`、`refillInterval` 等实现细节，**只进日志不进 HTTP 响应**。

### 5.3 默认策略与 dev 友好

| 配置 | 行为 | 适用场景 |
| --- | --- | --- |
| `rateLimiter = null`（默认） | 不做配额检查，启动时 WARN 日志 | dev / 单元测试 |
| `rateLimiter = TokenBucketRateLimiter` 但只配 GLOBAL | 只做全局上限 | 小规模部署 |
| 完整配置 GLOBAL + PRINCIPAL + AGENT | 多层防护 | 生产环境 |

WARN 日志示例：

```
[WARN] io.agent.helm.runtime - AgentRuntime built without RateLimiter; admission will not enforce any quota. Configure one in production via Builder.rateLimiter(...).
```

### 5.4 与 authorizer 的顺序

admission 管道的检查顺序：

```text
请求进入
  │
  ├─ [1] HelmAuthorizer.authorize(securityContext, operation)   ── 第 5 组件
  │       失败：抛 AuthorizationException（403）
  │       理由：先鉴权，避免匿名/未授权请求消耗配额
  │
  ├─ [2] RateLimiter.tryAcquire(keys)                            ── 本组件
  │       失败：抛 RateLimitExceededException（429）
  │
  ├─ [3] activeSessions 锁                                       ── 现有
  │       失败：抛 SessionBusyException（409）
  │
  ├─ [4] saveOperation(RUNNING) + appendEvent(STARTED)           ── 现有
  │
  └─ [5] 执行
```

authorizer 在 rate limiter 之前的原因：

- **避免匿名请求消耗配额**：如果 rate limit 在鉴权前，未授权请求会消耗 GLOBAL / PRINCIPAL 令牌，导致授权用户被拒。
- **principalId 依赖鉴权结果**：rate limit 的 PRINCIPAL 维度需要 principalId，该 id 由 authorizer 从 SecurityContext 中确认。

在 authorizer（第 5 组件）未落地前，rate limiter 独立工作，principalId 使用占位符（见 §5.1）。

### 5.5 错误码注册表

新增 `RATE_LIMITED` code，登记到 `docs/design/11-api-governance.md` 第 3.3 节 exception code 注册表：

| code | 异常类 | HTTP 状态码 | details 字段 | 说明 |
| --- | --- | --- | --- | --- |
| `RATE_LIMITED` | `RateLimitExceededException` | 429 | dimension, retryAfterMs, limit | admission 配额拒绝 |

### 5.6 模块依赖守则

| 模块 | 依赖 | 禁止依赖 |
| --- | --- | --- |
| helm-core `core.admission` | 无（纯 Java） | runtime/engine/HTTP/JDBC/Spring/logging |
| helm-core `core.error`（`RateLimitExceededException`） | `core.admission`（引用 `RateLimitDimension`） | 同上 |
| helm-runtime `runtime.admission`（`TokenBucketRateLimiter`） | helm-core `core.admission` | HTTP/JDBC/Spring |
| helm-http-core（429 映射） | helm-core `core.error` | runtime internals |

---

## 6. 测试策略

### 6.1 RateLimiterContractTest

放 helm-core test-jar，`io.agent.helm.core.admission`（test 包）。所有 `RateLimiter` 实现必须通过此合约测试。

```java
package io.agent.helm.core.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * RateLimiter 实现的合约测试基类。子类提供 {@link #createLimiter(long, Duration)} 工厂。
 */
public abstract class RateLimiterContractTest {

    /** 创建一个 capacity=5、refillInterval 每令牌的限流器。 */
    protected abstract RateLimiter createLimiter(long capacity, Duration refillInterval);

    // ----- 用例清单 -----

    @Test
    default void allowsUpToCapacityBurstInSameWindow() {
        RateLimiter limiter = createLimiter(5, Duration.ofSeconds(10));
        RateLimitKey key = RateLimitKey.principal("user1");

        for (int i = 0; i < 5; i++) {
            AcquisitionResult r = limiter.tryAcquire(key);
            assertThat(r.allowed()).as("acquire #" + i).isTrue();
        }
        AcquisitionResult sixth = limiter.tryAcquire(key);
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    default void rejectsWhenBucketEmpty() {
        RateLimiter limiter = createLimiter(1, Duration.ofSeconds(10));
        RateLimitKey key = RateLimitKey.agent("coding");

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        AcquisitionResult denied = limiter.tryAcquire(key);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    default void bucketRefillsAfterInterval() throws InterruptedException {
        RateLimiter limiter = createLimiter(1, Duration.ofMillis(100));
        RateLimitKey key = RateLimitKey.global();

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();

        Thread.sleep(150); // 等待补充 1 个令牌

        AcquisitionResult refilled = limiter.tryAcquire(key);
        assertThat(refilled.allowed()).isTrue();
    }

    @Test
    default void multipleDimensionsAreIndependent() {
        RateLimiter limiter = createLimiter(2, Duration.ofSeconds(10));
        RateLimitKey principal1 = RateLimitKey.principal("alice");
        RateLimitKey principal2 = RateLimitKey.principal("bob");

        // alice 耗尽她的桶
        assertThat(limiter.tryAcquire(principal1).allowed()).isTrue();
        assertThat(limiter.tryAcquire(principal1).allowed()).isTrue();
        assertThat(limiter.tryAcquire(principal1).allowed()).isFalse();

        // bob 的桶不受影响
        assertThat(limiter.tryAcquire(principal2).allowed()).isTrue();
    }

    @Test
    default void releaseRefundsTokenForPartialFailureRecovery() {
        RateLimiter limiter = createLimiter(1, Duration.ofSeconds(10));
        RateLimitKey key = RateLimitKey.principal("alice");

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        // 桶空了
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();

        // release 归还令牌
        limiter.release(key);
        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
    }

    @Test
    default void releaseOnUnacquiredKeyIsNoop() {
        RateLimiter limiter = createLimiter(1, Duration.ofSeconds(10));
        // 不应抛异常
        limiter.release(RateLimitKey.principal("never-acquired"));
    }

    @Test
    default void unconfiguredDimensionIsAlwaysAllowed() {
        RateLimiter limiter = createLimiter(1, Duration.ofSeconds(10));
        // createLimiter 只配置了某个 dimension，其他 dimension 应放行
        // （具体哪个 dimension 被 config 取决于子类实现，此处验证 global 放行）
        RateLimitKey key = RateLimitKey.global();
        // 连续 100 次都应通过（global 未配置或配置容量足够）
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire(key); // 不抛异常即可
        }
    }

    @Test
    default void concurrentAcquireIsThreadSafe() throws Exception {
        RateLimiter limiter = createLimiter(100, Duration.ofSeconds(10));
        RateLimitKey key = RateLimitKey.global();
        int threads = 20;
        int perThread = 10;

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicLong allowed = new java.util.concurrent.atomic.AtomicLong();

        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>(); // NOPMD
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    if (limiter.tryAcquire(key).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            }));
        }
        for (var f : futures) { // NOPMD
            f.get();
        }
        exec.shutdown();

        // 总请求 200，capacity 100，允许数应恰好等于 100（不超不欠）
        assertThat(allowed.get()).isEqualTo(100);
    }
}
```

#### 用例清单

| 用例 | 验证点 |
| --- | --- |
| `allowsUpToCapacityBurstInSameWindow` | 桶满时允许 capacity 次突发，第 capacity+1 次被拒 |
| `rejectsWhenBucketEmpty` | 桶空时拒绝，retryAfterMs > 0 |
| `bucketRefillsAfterInterval` | 等待 refillInterval 后桶恢复 1 个令牌 |
| `multipleDimensionsAreIndependent` | 不同 value 的 key 桶独立 |
| `releaseRefundsTokenForPartialFailureRecovery` | release 归还令牌后可再次 acquire |
| `releaseOnUnacquiredKeyIsNoop` | release 未持有的 key 不抛异常 |
| `unconfiguredDimensionIsAlwaysAllowed` | 未配置限流的 dimension 直接放行 |
| `concurrentAcquireIsThreadSafe` | 200 并发请求，capacity=100，恰好 100 个通过 |

### 6.2 Admission 集成测试

放 `helm-runtime` test 包，验证 `AgentRuntime` / `WorkflowRuntime` 的 admission 管道正确接入 rate limiter。

| 测试类 | 用例 | 验证点 |
| --- | --- | --- |
| `AgentRuntimeRateLimitTest` | `promptWithinLimitSucceeds` | 配额内 prompt 正常执行，返回 PromptResult |
| | `promptExceedingPrincipalLimitThrows` | per-principal 超限抛 `RateLimitExceededException`，details 含 dimension=PRINCIPAL |
| | `promptExceedingGlobalLimitThrows` | GLOBAL 维度超限抛异常 |
| | `rejectedPromptDoesNotCreateOperation` | 被拒后 `store.loadOperation(operationId)` 返回 Optional.empty |
| | `rejectedPromptDoesNotAppendEvent` | 被拒后 `store.eventsForOperation(operationId)` 返回空列表 |
| | `partialFailureRefundsAcquiredTokens` | AGENT 维度被拒时，GLOBAL + PRINCIPAL 令牌被 refund（通过再次 acquire 验证） |
| | `noRateLimiterAllowsAll` | 未配置 rateLimiter 时所有请求通过 |
| | `sessionBusyCheckedBeforeRateLimit` | 同 session 并发时，第二次请求抛 SessionBusyException 而非 RateLimitExceededException |
| `WorkflowRuntimeRateLimitTest` | `invokeWithinLimitSucceeds` | 配额内 workflow 正常执行 |
| | `invokeExceedingLimitThrows` | 超限抛 `RateLimitExceededException`，不创建 WorkflowRunRecord |
| | `rejectedInvokeDoesNotCreateRun` | `store.loadWorkflowRun(runId)` 返回 Optional.empty |
| `HttpRateLimitErrorTest` | `rateLimitedMapsTo429WithRetryAfter` | HTTP 层将 `RateLimitExceededException` 映射为 429 + `Retry-After` header |
| | `rateLimitErrorBodySafeToExpose` | response body 只含 code + details，不含 developerDetails |

---

## 7. 验收标准

### 7.1 SPI 与合约

- [ ] `helm-core` `core.admission` 包含 `RateLimiter`、`RateLimitKey`、`RateLimitDimension`、`AcquisitionResult` 四个类型，无 runtime/engine/HTTP 依赖。
- [ ] `RateLimitExceededException` 在 `core.error` 包，code=`RATE_LIMITED`，details 只含 dimension / retryAfterMs / limit。
- [ ] `RateLimiterContractTest` 在 helm-core test-jar 中发布，覆盖 §6.1 全部用例。
- [ ] `TokenBucketRateLimiter` 通过 `RateLimiterContractTest`。

### 7.2 Admission 集成

- [ ] `AgentRuntime.Builder.rateLimiter(RateLimiter)` 方法存在，默认 null。
- [ ] `WorkflowRuntime.Builder.rateLimiter(RateLimiter)` 方法存在，默认 null。
- [ ] `AgentRuntime.executePrompt` 在 session 锁之后、`saveOperation(RUNNING)` 之前调用 `tryAcquire`。
- [ ] `WorkflowRuntime.invoke` 在 `saveWorkflowRun(RUNNING)` 之前调用 `tryAcquire`。
- [ ] 被拒请求不创建 `OperationRecord` / `WorkflowRunRecord`，不追加 event。
- [ ] 被拒请求的 `RateLimitExceededException` 透传到调用方。
- [ ] 多维度检查的 partial-failure refund 正确（先前消耗的令牌被归还）。
- [ ] admission 顺序为 authorizer → rate limit → session lock → saveOperation（authorizer 未落地时 rate limit 独立工作）。

### 7.3 HTTP 集成

- [ ] `helm-http-core` 错误映射将 `RATE_LIMITED` 映射为 HTTP 429。
- [ ] 429 响应包含 `Retry-After` header（秒，向上取整）。
- [ ] 429 响应 body 只含 code + details，不含 developerDetails。
- [ ] `HttpRateLimitErrorTest` 通过。

### 7.4 安全与默认

- [ ] 未配置 `RateLimiter` 时启动 WARN 日志，所有请求通过。
- [ ] `RateLimitExceededException.details` 不含 remaining / currentTokens / refillRate 等内部状态。
- [ ] `developerDetails` 不出现在 HTTP 响应中。
- [ ] `RATE_LIMITED` code 登记到 `docs/design/11-api-governance.md` exception code 注册表。

### 7.5 验证命令

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

---

## 8. 风险与未决项

### 8.1 未决项

| ID | 未决项 | 影响 | 建议决策时点 |
| --- | --- | --- | --- |
| U-1 | `RateLimitExceededException` 的 `limit` 字段需要从 `RateLimiter` 实现侧获取当前 dimension 的配置容量。当前 SPI 的 `tryAcquire` 返回 `AcquisitionResult`（无 limit 字段）。选项：(a) 在 `AcquisitionResult` 增加 `limit` 字段；(b) SPI 增加 `long limitOf(RateLimitDimension)` 查询方法；(c) 异常内 `limit` 设为 0 或 -1 表示"未知"。 | 异常 details 的完整性 | 实施时决定，倾向 (a)，AcquisitionResult 增加 limit 字段最简单 |
| U-2 | `principalId` 来源：在 authorizer（第 5 组件）落地前，`AgentPromptRequest` 没有 principalId 字段。当前 `AgentPromptRequest(agentName, instanceId, sessionName, text)` 不含 principal。选项：(a) 暂用 `instanceId` 作为 principal；(b) `AgentPromptRequest` 增加 `principalId` 可选字段；(c) 等第 5 组件落地后一起实现 PRINCIPAL 维度。 | PRINCIPAL 维度可用性 | 倾向 (b)，与第 5 组件解耦但预留字段 |
| U-3 | Bucket 无淘汰机制：`TokenBucketRateLimiter` 的 `buckets` `ConcurrentHashMap` 随 key 增长无上限。per-session 维度会导致每个 session 创建一个桶，长期运行 OOM。选项：(a) 不支持 SESSION 维度（只支持 PRINCIPAL/AGENT/GLOBAL）；(b) 内置 LRU 淘汰（如 Caffeine）；(c) 文档标注需外层包装淘汰。 | 内存泄漏风险 | 倾向 (a) + 文档标注，SESSION 维度由 session 锁已隔离，速率限制通常不需要 per-session |
| U-4 | `dispatch` 被拒时的 handle 状态：当前返回 `OperationStatus.FAILED`，但被拒的 operation 实际未创建。调用方 `getOperation(operationId)` 返回 `Optional.empty`。是否需要 `OperationStatus.REJECTED` 或 `OperationHandle` 增加 `admissionRejected` 标志？ | dispatch 调用方能否区分"执行失败"与"admission 拒绝" | 倾向保持 FAILED + Optional.empty 语义，文档说明"operation 不存在 = admission 拒绝" |
| U-5 | 分布式限流：多 JVM 实例部署时，内存 token bucket 不共享。是否在本组件预留分布式 SPI（如 `RedisRateLimiter`）？ | 多实例生产部署 | 本组件不实现，SPI 已足够通用，未来作为独立 adapter 模块（如 `helm-rate-limit-redis`） |

### 8.2 风险

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| token bucket 突发流量压垮下游 provider | 中 | provider 限流 / 计费超支 | capacity 设为略高于稳态速率；provider 侧应有自己的重试/限流；GLOBAL 维度作为最后防线 |
| 多维度 partial-failure refund 在高并发下不精确 | 低 | 个别令牌可能未被精确 refund（因并发竞争） | 合约测试覆盖并发场景；refill 机制会自我修正（桶最终会恢复） |
| rate limit 拒绝不创建 operation 记录，审计困难 | 中 | 安全审计无法追溯被拒请求 | 通过 logging observer + metrics 记录被拒事件；未来可增加 admission event（不绑定 operationId） |
| `buckets` map 内存泄漏（per-session 场景） | 中 | 长期运行 OOM | U-3 决策：不支持 SESSION 维度或内置淘汰 |
| authorizer 未落地导致 PRINCIPAL 维度使用占位符 | 中 | per-principal 限流失效（所有请求共享同一 principal） | 文档明确标注临时方案；authorizer 落地后立即切换 |

---

## 9. 与其他组件的关系

### 9.1 依赖关系图

```text
                    ┌─────────────────────┐
                    │  11 API governance  │
                    │  (code 注册表)      │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  5 Authorizer       │
                    │  (SecurityContext)  │
                    └──────────┬──────────┘
                               │ principalId
                               ▼
                    ┌─────────────────────┐
                    │  7 Rate Limiting    │ ◄── 本文档
                    │  (admission 配额)   │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
    ┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
    │ 9 Durable Scale │ │ 6 Client SDK │ │ 8 Metrics    │
    │ (queue 在 RL 后)│ │ (429 重试)   │ │ (拒绝计数)   │
    └─────────────────┘ └──────────────┘ └──────────────┘
              │
              ▼
    ┌─────────────────┐
    │ 11 API gov      │
    │ (错误码对齐)    │
    └─────────────────┘
```

### 9.2 与各组件的协作

| 组件 | 关系 | 协作点 |
| --- | --- | --- |
| **#5 Authorizer** | 前置依赖 | authorizer 在 rate limiter 之前执行（先鉴权后限流）。authorizer 提供 `principalId`（从 `SecurityContext`），rate limiter 用它构造 `RateLimitKey.principal(principalId)`。authorizer 未落地前，rate limiter 可独立工作但 PRINCIPAL 维度使用占位符（见 U-2）。 |
| **#9 Durable Scale** | 后继依赖 | M11 durable queue 在 rate limit 之后：rate limit 在 admission 阶段同步检查，被拒直接返回 429；通过 rate limit 的请求才进入 durable queue 排队执行。本组件只做同步 rate limit，durable 排队归 #9。`RateLimiter` SPI 的 `release` 方法为 #9 的 concurrency limiting 预留扩展点，但本组件不使用执行后 release。 |
| **#6 Client SDK** | 被依赖 | `helm-client` 需要识别 429 响应并按 `Retry-After` header 做指数退避重试。SDK 应将 `RATE_LIMITED` error code 映射为可重试异常类型。 |
| **#11 API Governance** | 横向对齐 | `RATE_LIMITED` code 登记到 exception code 注册表。`RateLimitExceededException` 遵循 `HelmException(code, message, details, developerDetails)` 三段式约定。details 字段只含安全可暴露信息。 |
| **#8 Metrics & OTel** | 被依赖 | rate limit 拒绝应作为 metric 上报（如 `helm.admission.rate_limited{dimension=...}` counter）。`AcquisitionResult.remaining` 可作为 gauge 上报。本组件提供 hook 点（通过 `RuntimeEventObserver` 或直接日志），metrics 实现归 #8。 |
| **#2 Engine Hardening** | 无直接依赖 | engine events 与 rate limit 独立。rate limit 在 engine 调用之前，engine 不感知 rate limit。 |
| **#1 Streaming API** | 无直接依赖 | streaming 响应的 admission 检查在 prompt/dispatch 入口，与 streaming 传输层无关。 |

### 9.3 命名对齐

| 本组件类型 | 命名 | 与现有约定对齐 |
| --- | --- | --- |
| `RateLimitExceededException` code | `RATE_LIMITED` | 与 `SESSION_BUSY`、`AGENT_NOT_FOUND` 一致（无 `_ERROR` / `_FAILED` 后缀） |
| `RateLimiter` SPI 包 | `io.agent.helm.core.admission` | 与 `core.store`、`core.memory`、`core.event` 一致（core 子包） |
| `TokenBucketRateLimiter` 包 | `io.agent.helm.runtime.admission` | 与 `runtime` 现有内部实现同层 |
| `RateLimiterContractTest` | test-jar 发布 | 与 `RuntimeStoreContractTest`、`ModelProviderContractTest`、`SandboxContractTest` 一致 |
| Builder 方法 | `rateLimiter(RateLimiter)` | 与 `store(RuntimeStore)`、`memoryStore(MemoryStore)` 一致 |
