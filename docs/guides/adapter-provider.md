# Provider Adapter 实现指南

本指南说明如何为 Helm 实现一个新的 `ModelProvider` adapter，使其能接入新的模型供应商（如本地模型、企业网关或第三方 LLM 服务）。

## 1. 何时需要写 provider adapter

当 Helm 内置的 `helm-provider-openai`、`helm-provider-anthropic` 无法覆盖目标模型时，需要新 adapter。`ModelProvider` 是 Helm 的核心 SPI，所有模型调用都经 `AgentEngine` 的 `TurnRunner` 流式驱动。

## 2. SPI 概览

`ModelProvider` 接口位于 `io.agent.helm.core.model`：

```java
package io.agent.helm.core.model;

import io.agent.helm.core.error.HelmException;
import java.util.concurrent.Flow;

public interface ModelProvider {
    boolean supports(ModelRef model);
    Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) throws HelmException;
}
```

- `supports(ModelRef)`：判断该 provider 是否支持某个 `vendor/model` 引用。runtime 按注册顺序找到第一个 `supports` 返回 `true` 的 provider。
- `stream(ModelRequest)`：返回 JDK `Flow.Publisher<ModelStreamEvent>`，按顺序发布 `ContentDelta`、`ToolCallRequested`，最后以 `Completed`（含 `TokenUsage`）结束，或 `onError` 报错。

`ModelStreamEvent` 是 sealed 接口，子类型包括 `ContentDelta`、`ToolCallRequested`、`Completed` 等。

## 3. 实现步骤

### 3.1 创建模块

artifactId 遵循命名规则 `helm-provider-<vendor>`：

```text
helm-provider-<vendor>/
  pom.xml
  src/main/java/io/agent/helm/provider/<vendor>/
    <Vendor>ModelProvider.java
  src/test/java/io/agent/helm/provider/<vendor>/
    <Vendor>ModelProviderContractTest.java
```

模块 `pom.xml` 仅依赖 `helm-core`（compile）+ 测试期 `helm-core` test-jar（test scope，提供 `ModelProviderContractTest`）。

### 3.2 实现 ModelProvider

```java
package io.agent.helm.provider.example;

import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class ExampleModelProvider implements ModelProvider {
    private final String apiKey;
    private final String baseUrl;

    public ExampleModelProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean supports(ModelRef model) {
        return "example".equals(model.vendor());
    }

    @Override
    public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) throws ProviderException {
        // 1. 将 ModelRequest 映射为供应商 HTTP 请求
        // 2. 发起流式调用
        // 3. 将供应商 token 流映射为 ModelStreamEvent
        // 4. 错误映射为 ProviderException（见 §5）
        SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
        // ... 订阅供应商流并转发为 ModelStreamEvent
        return publisher;
    }
}
```

### 3.3 通过合约测试

必须 extend `ModelProviderContractTest`（位于 `helm-core` test-jar）：

```java
package io.agent.helm.provider.example;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelProviderContractTest;
import io.agent.helm.core.model.TokenUsage;

class ExampleModelProviderContractTest extends ModelProviderContractTest {
    private final ExampleModelProvider provider = new ExampleModelProvider("key", "http://localhost");

    @Override
    protected ModelProvider provider() { return provider; }

    @Override
    protected ModelRef supportedModel() { return new ModelRef("example", "ex-1"); }

    @Override
    protected void prepareTerminalTextStream(String text, TokenUsage usage) {
        // 让 provider 在下次 stream 时发出 text + Completed(usage)
    }

    @Override
    protected void prepareToolCallStream(String toolCallId, String toolName, Object input,
                                         String finalText, TokenUsage usage) {
        // 让 provider 发出 ToolCallRequested + ContentDelta(finalText) + Completed(usage)
    }

    @Override
    protected void prepareErrorStream() {
        // 让 provider 在 stream 时失败
    }
}
```

合约测试覆盖：事件顺序、tool-call 发射、错误映射为 `HelmException`、`supports` 边界。HTTP provider 可用 WireMock stub 后端。

## 4. Credential 来源

API key 等凭据**绝不硬编码**，应通过以下方式获取：

- 环境变量：`System.getenv("EXAMPLE_API_KEY")`。
- 配置文件：应用层传入，provider 接受构造参数。
- Secret manager：生产环境由应用层注入。

provider 构造函数只接受已解析的 credential，不自行读取配置文件（保持 provider 可测试、可注入）。

## 5. 错误映射

供应商错误必须映射为 `ProviderException`（`HelmException` 子类），使用注册的 error code：

| 场景 | code | HTTP status |
| --- | --- | --- |
| 供应商返回错误 | `PROVIDER_ERROR` | 502 |
| 供应商限流 | `PROVIDER_RATE_LIMITED` | 429 |
| 供应商超时 | `PROVIDER_TIMEOUT` | 504 |

```java
throw new ProviderException("PROVIDER_RATE_LIMITED", "example rate limited",
        /*details*/ Map.of("retryAfterSeconds", 30), /*developerDetails*/ response.body());
```

`developerDetails` 不进 HTTP 响应与事件 payload，仅在服务端日志中 redact 后可见。

## 6. Credential redaction

API key 不应出现在：

- `RuntimeEventRecord.payload`（runtime 已 redact，但 provider 自身也不应写入）。
- `HelmException.developerDetails`（避免完整请求 header 含 `Authorization`）。
- 日志输出。

对齐 [docs/design/11-api-governance.md](../design/11-api-governance.md) §3.3.3 安全规则。

## 7. 依赖约束

provider adapter 只依赖 `helm-core`，不依赖 `helm-runtime`、`helm-agent-engine` 或其他 adapter。这保证 provider 可独立编译与发布。

## 8. 发布与 BOM 注册

模块完成后，在 `helm-bom` 的 `<dependencyManagement>` 中注册新模块坐标。详见 [docs/design/10-release-engineering.md](../design/10-release-engineering.md) §3.2.3。
