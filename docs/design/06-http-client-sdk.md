# 06 · HTTP Client SDK（`helm-client`）

> 状态：设计草案 · 来源：[`docs/roadmap.md`](../roadmap.md) M6「Java client SDK」+ 模块图 `helm-client`
> 关联：[#1 streaming](01-streaming-api.md) · [#5 authorizer](05-authorizer-security-context.md) · [#7 rate limiting](07-rate-limiting.md) · [#11 api governance](11-api-governance.md)

## 1. 背景与目标

### 1.1 为什么需要

Helm 已通过 `helm-http-core` + `helm-http-servlet` 把 runtime 暴露为 HTTP 服务（见 `HelmHttpRoutes`）。但外部 Java 应用想调用 Helm，目前只能自己拼 HTTP 请求、自己解析 `{"error":{code,message,details}}`、自己处理超时与重试。这导致：

- 每个消费方重复实现错误映射，code 与 `HelmException` 子类的对应关系容易漂移。
- streaming（#1）发布后，SSE 解析逻辑散落各处。
- 认证头（#5 的 principal header）注入方式不统一。

`helm-client` 提供一个与 `helm-runtime` API 形态对称的 Java 客户端，把 HTTP 细节、错误映射、SSE 解析、重试集中到一处，让消费方像调用本地 runtime 一样调用远程 Helm。

### 1.2 目标

| 目标 | 说明 |
| --- | --- |
| 对齐 runtime API | `HelmClient` 方法名/参数语义与 `AgentRuntime`/`WorkflowRuntime` 对齐，降低学习成本 |
| 三种调用风格 | 同步、异步（`CompletableFuture`）、流式（`Flow.Publisher`） |
| 错误对齐 | HTTP error response 还原为 `HelmException` 子类，code 透传 |
| 轻依赖 | 用 JDK 21 `java.net.http.HttpClient`，不引入 OkHttp/ Retrofit 等重型栈 |
| 复用 DTO | 共享 `helm-http-core` 的 request/response 与 error 结构，避免双份漂移 |

### 1.3 不解决什么

- 不做服务发现 / 负载均衡（留待 #9 durable scale 的 ProviderRouter 思路）。
- 不做自己的认证协议——只透传 header，认证由 #5 authorizer 在服务端裁决。
- 不直接依赖 `helm-runtime`（client 是远程调用，不应把 runtime 拖进消费方 classpath）。

## 2. 现状与缺口

### 2.1 现有 HTTP 路由

`HelmHttpRoutes.router(...)` 注册了以下路由（`helm-http-core/.../HelmHttpRoutes.java:31-43`）：

| 方法 | 路径 | runtime 调用 |
| --- | --- | --- |
| POST | `/agents/{agent}/instances/{instance}/sessions/{session}/prompt` | `AgentRuntime.prompt` |
| POST | `/agents/{agent}/dispatch` | `AgentRuntime.dispatch` |
| POST | `/workflows/{workflow}/invoke` | `WorkflowRuntime.invoke` |
| GET | `/operations/{id}` | `AgentRuntime.getOperation` |
| GET | `/operations/{id}/events` | `AgentRuntime.getOperationEvents` |
| GET | `/sessions/{id}/operations` | 按 sessionId 过滤 `listOperations` |
| GET | `/workflow-runs/{id}` | `WorkflowRuntime.getRun` |
| GET | `/workflows/{workflow}/runs` | 按 workflow 过滤 `listRuns` |

### 2.2 缺口

1. **无 client 模块**：roadmap 模块图列出 `helm-client` 为待实现（`docs/design/README.md` §3.2 ◯ #6）。
2. **runtime 已有但 HTTP 未暴露的能力**：`listSessions` / `getSession` / `resetSession` / `listOperations` / `getRunEvents` 在 runtime 层已实现（`AgentRuntime.java:105-125`、`WorkflowRuntime.java:123-125`），但 `HelmHttpRoutes` 未注册路由。client 需假设这些路由会在 M6/M7 收尾时补齐，并在文档中显式标注未覆盖项（见 §3.8）。
3. **错误结构无 client 侧映射**：`HttpErrors`（`HttpErrors.java:30-45`）产出 `{"error":{code,message,details}}`，`statusFor`（`HttpErrors.java:47-58`）已定义 code→HTTP status 映射，但消费方需自己解析。
4. **无 SSE 客户端**：streaming #1 发布后需 SSE 解析。

### 2.3 roadmap 出处

- `docs/roadmap.md` M6 交付项「Java client SDK」。
- `docs/roadmap.md` §3.1「流式响应 API 暴露」与 client promptStream 强相关。
- M6 验收：HTTP opt-in、每个 route 执行前可授权、error response 只含 code + safe details——client 必须对齐这三条契约。

## 3. 设计方案

### 3.1 模块定位与依赖

`helm-client` 是集成层模块，依赖关系：

```text
helm-client
  ├── helm-core        （HelmException 体系、record 类型：PromptResult/OperationRecord/...）
  └── helm-http-core  （共享 DTO 与 HttpErrors 结构，仅编译期复用 record/常量）
```

**不依赖** `helm-runtime` / `helm-http-servlet` / `helm-spring-boot-starter`。`helm-http-core` 在 client 端只用作类型来源（`HelmHttpResponse` record、`HttpErrors` 的 code 常量），不引入 Servlet API。

`pom.xml` 关键依赖（JDK 自带 HttpClient，无额外 HTTP 库）：

```xml
<dependency>
  <groupId>io.agent.helm</groupId>
  <artifactId>helm-core</artifactId>
</dependency>
<dependency>
  <groupId>io.agent.helm</groupId>
  <artifactId>helm-http-core</artifactId>
</dependency>
<!-- 测试：WireMock -->
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock-standalone-jetty12</artifactId>
  <scope>test</scope>
</dependency>
```

### 3.2 DTO 复用决策

复用 `helm-http-core` 的 record（`HelmHttpRequest`/`HelmHttpResponse` 仅在 server 侧有意义，client 复用的是 `OperationRecord`/`WorkflowRunRecord`/`RuntimeEventRecord`/`AgentSessionState` 等 core 类型）。

**利**：序列化结构与 server 一致，无双份 schema 漂移。
**弊**：client 间接依赖 `helm-http-core`（含 Jackson）。可接受——`helm-http-core` 已是轻量 DTO 模块。若后续要彻底解耦，可提取 `helm-http-api` 纯 DTO 模块（与 #11 一起决策）。

### 3.3 `HelmClient` 接口

放 `io.agent.helm.client`。方法命名与 `AgentRuntime`/`WorkflowRuntime` 对称：

```java
public interface HelmClient {
    // —— Agent ——
    PromptResult prompt(String agent, String instance, String session, String text);
    OperationHandle dispatch(String agent, AgentDispatchRequest request);
    Optional<OperationRecord> getOperation(String operationId);
    List<RuntimeEventRecord> getOperationEvents(String operationId);
    List<OperationRecord> listOperations();
    List<OperationRecord> sessionOperations(String sessionId);

    // —— Session（依赖 M6/M7 补齐路由）——
    List<AgentSessionState> listSessions();
    Optional<AgentSessionState> getSession(String sessionId);
    void resetSession(String sessionId);

    // —— Workflow ——
    <I, O> WorkflowRunHandle<O> invokeWorkflow(String workflow, I input);
    Optional<WorkflowRunRecord> getRun(String runId);
    List<WorkflowRunRecord> workflowRuns(String workflow);
    List<RuntimeEventRecord> getRunEvents(String runId);  // 依赖路由补齐
}
```

`AgentDispatchRequest` 是 client 侧 record（`instance`/`session`/`text`），对应 `POST /agents/{agent}/dispatch` 的 body（`HelmHttpRoutes.java:58-71`）。

### 3.4 异步与流式变体

```java
public interface HelmAsyncClient {
    CompletableFuture<PromptResult> prompt(String agent, String instance, String session, String text);
    CompletableFuture<OperationHandle> dispatch(String agent, AgentDispatchRequest request);
    CompletableFuture<Optional<OperationRecord>> getOperation(String operationId);
    // ... 其余方法返回 CompletableFuture
}

public interface HelmStreamClient {
    /** 增量返回 prompt 事件；对齐 #1 的 PromptStreamEvent。 */
    Flow.Publisher<PromptStreamEvent> promptStream(
            String agent, String instance, String session, String text);
}
```

`HelmClient` 实现同时持有 `HelmAsyncClient` 与 `HelmStreamClient`，通过 `HelmClient.async()` / `HelmClient.stream()` 暴露。

### 3.5 底层 HTTP 客户端

用 JDK 21 `java.net.http.HttpClient`，避免重型依赖：

```java
final class JdkHttpTransport implements HttpTransport {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Function<HttpRequest.Builder, HttpRequest.Builder> headerInjector;
    private final RetryPolicy retry;

    JdkHttpTransport(URI baseUrl, HelmClientConfig config) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .executor(config.executor())
                .build();
        // ...
    }

    HelmHttpResponse send(String method, String path, Object body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        req = headerInjector.apply(req);          // 注入 auth header（§3.7）
        if (body != null) req.method(method, ofString(mapper.writeValueAsString(body)));
        else req.method(method, noBody());
        return executeWithRetry(req);
    }
}
```

### 3.6 错误映射

`ClientErrorMapper` 把 `HelmHttpResponse`（status ≥ 400）还原为 `HelmException` 子类。code 透传 server code（`HttpErrors.java:21-26` 已在响应里带回 code/details），status 对齐 `statusFor`（`HttpErrors.java:47-58`）：

| HTTP status | code 模式 | 还原为 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | `AuthorizationException`（#5 新增） |
| 403 | `FORBIDDEN` | `AuthorizationException` |
| 404 | `NOT_FOUND` / `AGENT_NOT_FOUND` / `WORKFLOW_NOT_FOUND` | 对应 `*NotFoundException` |
| 400 | `VALIDATION_FAILED` | `ValidationException` |
| 409 | `SESSION_BUSY` | `SessionBusyException` |
| 413 | `CONTEXT_OVERFLOW` | `ContextOverflowException` |
| 429 | `PROVIDER_RATE_LIMITED` / `RATE_LIMITED`（#7） | `ProviderException` / `RateLimitExceededException`（#7） |
| 504 | `PROVIDER_TIMEOUT` | `ProviderException` |
| 502 | `PROVIDER_ERROR` | `ProviderException` |
| 5xx 其它 | `INTERNAL_ERROR` | `HelmException`（基类） |

未识别 code 退化为 `HelmException(code, message, details, Map.of())`，不丢 code。

```java
static HelmException toException(HelmHttpResponse res) {
    ErrorBody err = parse(res.body());              // {code, message, details}
    return switch (err.code()) {
        case "AGENT_NOT_FOUND"      -> new AgentNotFoundException(err.message(), err.details(), Map.of());
        case "SESSION_BUSY"         -> new SessionBusyException(err.message(), err.details(), Map.of());
        case "CONTEXT_OVERFLOW"     -> new ContextOverflowException(err.message(), err.details(), Map.of());
        case "RATE_LIMITED"          -> new RateLimitExceededException(err.message(), err.details(), Map.of());
        // ...
        default                      -> new HelmException(err.code(), err.message(), err.details(), Map.of()) {};
    };
}
```

### 3.7 认证

`headerInjector` 让消费方注入任意 header，对齐 #5 的 principal header 约定：

```java
HelmClient client = HelmClient.builder()
        .baseUrl(URI.create("https://helm.example.com"))
        .bearerToken(token)                         // 等价 .headerInjector(b -> b.header("Authorization", "Bearer " + token))
        .principalHeader("user-42", Map.of("tenant", "acme"))  // 注入 X-Helm-Principal / X-Helm-Tenant
        .build();
```

`bearerToken` / `principalHeader` 是 `headerInjector` 的便捷工厂。credential 不进日志（§5）。

### 3.8 重试与超时

`RetryPolicy`：

- 默认重试条件：`408`、`429`（遵守 `Retry-After`）、`503`、`504`、`IOException`。
- 仅对**幂等**方法重试（GET 全部、POST dispatch/invoke 若消费方声明 idempotent）。`prompt` 默认**不**重试（已有副作用：session 历史已追加）。
- 最大重试 2 次，指数退避。

超时：`connectTimeout`（默认 5s）、`requestTimeout`（默认 30s，prompt 可放宽至 120s）、`streamTimeout`（promptStream 的整体预算）。

### 3.9 SSE 解析（promptStream）

`POST /agents/{agent}/.../prompt/stream`（#1 新增路由）返回 `text/event-stream`，每行 `data: <json>\n\n`。`SseParser` 把字节流切成事件，反序列化为 `PromptStreamEvent`（#1 定义）：

```java
final class SseParser {
    // 订阅 HttpClient.BodySubscribers.ofLines()，按空行切 frame
    // 每 frame 取 "data:" 前缀行，合并为 JSON，反序列化为 PromptStreamEvent
    Flow.Publisher<PromptStreamEvent> parse(Flow.Publisher<String> lines);
}
```

`promptStream` 返回的 `Flow.Publisher` 直接转发 parser 输出；背压策略：每收到一个事件 `request(1)`。

### 3.10 配置 Builder

```java
HelmClient.builder()
    .baseUrl(URI)
    .connectTimeout(Duration)
    .requestTimeout(Duration)
    .executor(Executor)
    .bearerToken(String) | .headerInjector(Function)
    .principalHeader(String, Map)
    .retry(RetryPolicy)
    .objectMapper(ObjectMapper)
    .build();
```

## 4. 数据流与时序

### 4.1 同步调用时序

```text
Caller → HelmClient.prompt
  → headerInjector 注入 auth
  → JdkHttpTransport.send(POST .../prompt, {"text":...})
    → [429?] RetryPolicy.sleep(Retry-After) → 重发
    → [status<400] 反序列化 → PromptResult
    → [status>=400] ClientErrorMapper → HelmException → throw
```

### 4.2 异步调用时序

```text
Caller → HelmAsyncClient.dispatch
  → CompletableFuture.supplyAsync(() -> transport.send(...), executor)
  → exceptionally(ClientErrorMapper::toException)
Caller.await → OperationHandle
```

### 4.3 流式调用时序

```text
Caller.subscribe → HelmStreamClient.promptStream
  → HttpClient.send(POST .../prompt/stream, Accept: text/event-stream)
  → BodySubscribers.ofLines → SseParser → Flow.Publisher<PromptStreamEvent>
  → Caller.onNext(ContentDelta) ... onNext(OperationCompleted) → onComplete
  → [中途 onError] ClientErrorMapper → onError(HelmException)
```

### 4.4 重试时序

```text
send → 429 + Retry-After: 2
  → RetryPolicy.shouldRetry(429, attempt=1) = true
  → sleep(2s)
  → send → 200 → 返回
```

## 5. 安全与边界

- **credential 不进日志**：`JdkHttpTransport` 的日志只记 method/path/status/durationMs，**不记** Authorization / X-Helm-Principal header 值与 body。
- **错误透传不泄漏**：client 只透传 server 已脱敏的 `details`，不构造新的敏感字段（对齐 `HttpErrors` 的 safe details 契约）。
- **timeout 强制**：所有请求必须有 `requestTimeout`，防止 socket 永久挂起；promptStream 有 `streamTimeout` 整体预算。
- **依赖守则**：`helm-client` 不依赖 `helm-runtime`/servlet/spring；只依赖 `helm-core` + `helm-http-core` + JDK。
- **transport 可替换**：`HttpTransport` 是内部接口，未来可换 OkHttp / Spring WebClient 实现，不污染 `HelmClient` API。

## 6. 测试策略

### 6.1 契约测试 `HelmClientContractTest`

用 WireMock 模拟 server，覆盖每个方法。每个用例 stub + 断言：

| 方法 | success stub | error stub | timeout stub |
| --- | --- | --- | --- |
| prompt | 200 `{"operationId","text"}` | 409 SESSION_BUSY | - |
| dispatch | 202 `{"operationId","status"}` | 429 RATE_LIMITED | - |
| getOperation | 200 OperationRecord / 404 | 404 NOT_FOUND | - |
| getOperationEvents | 200 `{"events":[...]}` | - | - |
| invokeWorkflow | 200 `{"runId","result"}` | 502 PROVIDER_ERROR | - |
| getRun | 200 / 404 | - | - |
| listSessions / getSession / resetSession | 200 / 204 | 403 FORBIDDEN | -（依赖路由补齐） |
| promptStream | 200 `text/event-stream` | - | streamTimeout |

### 6.2 错误映射测试

`ClientErrorMapperTest`：对每个 code 断言还原的 `HelmException` 子类、code 透传、details 透传。

### 6.3 重试测试

`RetryPolicyTest`：429 + Retry-After → 重试成功；503 → 重试至 maxAttempts；非幂等 prompt 不重试。

### 6.4 SSE 解析测试

`SseParserTest`：多行 data 拼接、空行切 frame、`event:` 字段忽略（仅处理 data）、partial chunk。

### 6.5 集成测试

`HelmClientHttpIT`：起真实 `HelmHttpServlet`（Jetty，参考 `HttpErrorContractTest` 已有的 Jetty 用法），client 调用端到端。

## 7. 验收标准

- [ ] `helm-client` 模块存在，`mvn verify` 全绿。
- [ ] `HelmClient` 方法与 `AgentRuntime`/`WorkflowRuntime` 公共方法一一对应（runtime 已有的能力）。
- [ ] 错误还原：每个 server code → 对应 `HelmException` 子类，code/details 透传。
- [ ] credential 不进任何日志输出。
- [ ] JDK HttpClient，无 OkHttp/Retrofit 依赖。
- [ ] 不依赖 `helm-runtime`/servlet/spring。
- [ ] `HelmClientContractTest`（WireMock）覆盖所有方法 success/error/timeout/streaming。
- [ ] promptStream SSE 端到端通过（依赖 #1 路由）。
- [ ] HTTP opt-in（client 不启动 server，仅消费）。
- [ ] error response 只含 code + safe details（透传 server 已脱敏结构）。

## 8. 风险与未决项

### 8.1 风险

| 风险 | 缓解 |
| --- | --- |
| HTTP 路由不全（listSessions/getSession/resetSession/getRunEvents 未注册）导致 client 方法无路由可调 | §3.8 显式标注；推动 M6/M7 补齐路由，client 用 `@Preview` 标注未覆盖方法 |
| code 与 status 映射随 server 演进漂移 | `ClientErrorMapper` 与 `HttpErrors.statusFor` 共享测试基线；#11 ErrorCode 注册表统一 |
| SSE 在代理/网关被缓冲导致流式失效 | 文档列出部署要求（禁用 buffering、`X-Accel-Buffering: no`）；client 不负责绕过 |
| JDK HttpClient 在某些容器行为差异 | 保留 `HttpTransport` SPI，可换实现 |

### 8.2 未决项

- U-1：是否提取 `helm-http-api` 纯 DTO 模块，让 client 只依赖 DTO 而非整个 `helm-http-core`（与 #11 一起决策）。
- U-2：`prompt` 是否提供幂等选项（`Idempotency-Key` header）以允许安全重试——与 #9 idempotency policy 关联。
- U-3：是否提供 Spring `RestClient`/WebFlux 适配子模块，还是只保留 JDK HttpClient。
- U-4：client 是否内置 metrics（micrometer），还是留给 #8 observer 在 server 侧统计。
- U-5：`HelmClient` 是否纳入 `helm-bom`（#10）对外发布。

## 9. 与其他组件的关系

| 组件 | 关系 |
| --- | --- |
| #1 streaming | `promptStream` 直接消费 #1 的 SSE 路由与 `PromptStreamEvent` 类型 |
| #5 authorizer | `principalHeader` 注入 #5 约定的 `X-Helm-Principal`/`X-Helm-Tenant` |
| #7 rate limiting | 429 + Retry-After 重试；`RateLimitExceededException` 还原 |
| #11 api governance | DTO 复用、code 注册表对齐、`@Preview` 标注未稳定方法 |
| #10 release engineering | client 纳入 `helm-bom`；WireMock 契约测试纳入 testkit |
| `helm-http-core` ✓ | 复用 record 与 `HttpErrors` code 常量；不引入 servlet |
| `helm-runtime` ✓ | API 形态对称，但无编译依赖 |
