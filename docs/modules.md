# Helm 模块说明

每个 Helm 模块的用途、依赖、关键 API 与示例。应用按需引入对应模块；core 三模块（`helm-core`/`helm-agent-engine`/`helm-runtime`）无 Spring/Servlet/JDBC/SDK 依赖。

## 模块清单

| 模块 | 用途 | 依赖 |
| --- | --- | --- |
| `helm-core` | 公开 API/SPI：Agent/Workflow/Tool/Skill/Sandbox/ModelProvider/RuntimeStore/MemoryStore/事件/错误/类型。 | 仅 JDK |
| `helm-agent-engine` | agent loop、turn 执行、流式聚合、tool-call 编排、engine 事件。 | helm-core |
| `helm-runtime` | registries、AgentRuntime/WorkflowRuntime、admission（authorizer + rate limiter）、session/memory 管理、FakeProvider、InMemoryRuntimeStore。 | helm-core + helm-agent-engine |
| `helm-provider-openai` | OpenAI 兼容 provider 适配器。 | helm-core |
| `helm-provider-anthropic` | Anthropic provider 适配器。 | helm-core |
| `helm-sandbox-local` | InMemorySandbox + LocalSandbox（受控 FS + 可选 shell）。 | helm-core |
| `helm-http-core` | 框架无关 HTTP DTO、路由、错误映射、SSE route、authorizer 集成。 | helm-core + helm-runtime |
| `helm-http-servlet` | Jakarta Servlet adapter（唯一 Servlet 模块）。 | helm-http-core + servlet API |
| `helm-cli` | Picocli：`helm run`/`dev`/`operations`/`runs`/`run-detail`。 | helm-runtime + helm-http-servlet |
| `helm-spring-boot-starter` | 自动配置、bean discovery、`helm.http.enabled` 挂载 servlet。 | helm-runtime + helm-http-servlet + Spring Boot |
| `helm-persistence-jdbc` | JdbcRuntimeStore + JdbcMemoryStore + Flyway 迁移。 | helm-core + Flyway + H2(test) |
| `helm-observability-logging` | LoggingRuntimeObserver（SLF4J 结构化日志）。 | helm-core + SLF4J |
| `helm-observability-opentelemetry` | OpenTelemetryRuntimeObserver（metrics + tracing）+ @Redact + RedactingEventRedactor。 | helm-core + OpenTelemetry |
| `helm-memory-semantic` | SemanticMemoryStore 装饰器 + InMemoryEmbeddingStore + FakeEmbeddingProvider。 | helm-core |
| `helm-client` | HTTP Client SDK（sync/async/streaming + 错误映射 + SSE 解析）。 | helm-core + helm-http-core + Jackson |
| `helm-bom` | 版本管理 BOM（import 即可统一所有 helm 模块版本）。 | pom |

## 快速使用

### 引入 BOM

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 定义 Agent（helm-core + helm-runtime）

```java
public final class AssistantAgent implements AgentDefinition {
    @Override
    public String name() { return "assistant"; }

    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
                .model("fake/test")
                .instructions("You are helpful.")
                .tool(new EchoTool())
                .build();
    }
}

AgentRuntime runtime = AgentRuntime.builder()
        .agent(new AssistantAgent())
        .provider(new FakeProvider("fake"))
        .store(new InMemoryRuntimeStore())
        .authorizer((ctx, action, resource) -> AuthorizationResult.allow())
        .build();
PromptResult result = runtime.prompt(new AgentPromptRequest("assistant", "i1", "default", "hi"));
```

### HTTP 服务（helm-http-core + helm-http-servlet）

```java
HelmHttpRouter router = HelmHttpRoutes.router(agentRuntime, workflowRuntime,
        HelmAuthorizer.allowAll(), SecurityContextExtractor.header());
// Servlet 容器挂载 new HelmHttpServlet(router)
```

### Java 客户端（helm-client）

```java
HelmClient client = HelmClient.builder()
        .baseUrl("http://localhost:8080")
        .bearerToken(token)
        .build();
PromptResult result = client.prompt("assistant", "i1", "default", "hi");
client.promptStream("assistant", "i1", "default", "hi")
        .subscribe(new Flow.Subscriber<>() { ... });
```

### 持久化（helm-persistence-jdbc）

```java
DataSource ds = ...;  // H2/PostgreSQL
HelmSchema.migrate(ds);
RuntimeStore store = new JdbcRuntimeStore(ds);
MemoryStore memory = new JdbcMemoryStore(ds);
AgentRuntime.builder().store(store).memoryStore(memory).build();
```

### 可观测性（helm-observability-opentelemetry）

```java
OpenTelemetry otel = OpenTelemetrySDK.builder().build();
RuntimeEventObserver observer = new OpenTelemetryRuntimeObserver(otel);
// 注册到 runtime event bus
```

## SPI 与合约测试

每个 SPI 在 `helm-core/src/test/java` 提供合约测试基类（发布 test-jar），in-tree 与外部 adapter 共用：

| SPI | 合约测试基类 |
| --- | --- |
| `ModelProvider` | `ModelProviderContractTest` |
| `Sandbox` | `SandboxContractTest` |
| `RuntimeStore`（含子接口） | `RuntimeStoreContractTest` |
| `MemoryStore` | `MemoryStoreContractTest` |
| `EmbeddingProvider`/`EmbeddingStore` | `helm-memory-semantic` 内测试（后续提取 testkit） |

实现新 adapter 时继承对应基类并实现抽象方法，确保行为一致。
