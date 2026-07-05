# Observability Adapter 实现指南

本指南说明如何为 Helm 实现可观测性 adapter，基于 `RuntimeEventObserver` SPI 接收运行时事件。

## 1. SPI 概览

`RuntimeEventObserver` 接口位于 `io.agent.helm.core.event`：

```java
public interface RuntimeEventObserver {
    void onEvent(RuntimeEventRecord event);
}
```

`RuntimeEventRecord` 是不可变 record，包含：

```java
public record RuntimeEventRecord(
        String id,
        String operationId,
        String workflowRunId,
        long sequence,
        String type,
        Map<String, Object> payload,
        Instant createdAt) {}
```

runtime 在持久化事件后调用 observer。事件到达 observer 前已由 runtime redact，observer 不应引入新的敏感内容。

## 2. 默认行为约束

实现 observer 时遵循以下默认策略：

### 2.1 只记 metadata

默认只记录事件 metadata（`type`、`operationId`、`workflowRunId`、`sequence`、`createdAt`），不记录完整 `payload`。参考 `LoggingRuntimeObserver` 的实现：

```java
@Override
public void onEvent(RuntimeEventRecord event) {
    String type = event.type();
    if (type.startsWith("error.") || type.endsWith(".failed")) {
        logger.warn("helm event {} operation={} workflow={} sequence={}",
                type, event.operationId(), event.workflowRunId(), event.sequence());
    } else if (type.contains("model.") || type.contains("tool.")) {
        logger.debug("helm event {} operation={} workflow={} sequence={}",
                type, event.operationId(), event.workflowRunId(), event.sequence());
    } else {
        logger.info("helm event {} operation={} workflow={} sequence={}",
                type, event.operationId(), event.workflowRunId(), event.sequence());
    }
}
```

### 2.2 不阻塞 runtime 主线程

observer 的 `onEvent` 在 runtime 线程调用。实现应：

- 快速返回（如直接写 SLF4J logger）。
- 若需重 IO（如发到远程 collector），用内部队列异步处理，`onEvent` 只入队。
- 队列满时丢弃或降级，不阻塞 runtime。

## 3. Redaction

事件 payload 已由 runtime redact（对齐 [docs/design/11-api-governance.md](../design/11-api-governance.md) §3.3.3）。observer 仍须遵守：

- 不从 payload 中提取 credential 并重新序列化到更高 log level。
- 若需记录 payload 用于 debug，确保对应 logger level 为 `DEBUG` 或更低。
- `@Redact` 标注的字段由 runtime 处理，observer 无需感知。

## 4. metrics / tracing 接入

`RuntimeEventObserver` 是 metrics 与 tracing adapter 的统一接入点：

- **metrics adapter**：在 `onEvent` 中根据 `type` 递增 counter 或记录 histogram（如 `helm.tool.calls`、`helm.model.latency`）。组件 #8（metrics / OpenTelemetry）落地后，`helm-observability-opentelemetry` 模块提供 OTel 实现。
- **tracing adapter**：以 `operationId` 为 span 关联，记录 turn / tool call / provider call 的 span。`workflowRunId` 用于跨 operation 关联。
- **logging adapter**：现有 `helm-observability-logging` 提供结构化日志实现。

## 5. 实现示例

```java
package io.agent.helm.observability.example;

import io.agent.helm.core.event.RuntimeEventObserver;
import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ExampleMetricsObserver implements RuntimeEventObserver {
    private final ConcurrentLinkedQueue<RuntimeEventRecord> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void onEvent(RuntimeEventRecord event) {
        // 快速入队，不阻塞 runtime
        queue.offer(event);
    }

    // 后台线程消费 queue，更新 metrics
    public void drain() {
        RuntimeEventRecord event;
        while ((event = queue.poll()) != null) {
            // 按 type 更新 counter / histogram
        }
    }
}
```

## 6. SPI 稳定性

`RuntimeEventObserver` 标注为 `@Experimental`（对齐 [docs/design/11-api-governance.md](../design/11-api-governance.md) §3.2.3）。pre-1.0 阶段该 SPI 可能调整，adapter 实现者应预期接口在 minor 版本间可能变化。

## 7. 依赖约束

observability adapter 只依赖 `helm-core`（SPI 类型）+ 观测后端库（SLF4J / OTel SDK / Micrometer 等）。不依赖 `helm-runtime` 或 `helm-agent-engine`。

## 8. 发布与 BOM 注册

模块完成后在 `helm-bom` 注册坐标。详见 [docs/design/10-release-engineering.md](../design/10-release-engineering.md) §3.2.3。
