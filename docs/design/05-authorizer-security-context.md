# 5. Authorizer / SecurityContext

Helm 在系统设计 Milestone 1–5 之后已具备完整 MVP 基座，HTTP/Servlet/CLI/Spring Boot starter 也已落地。但 `AgentRuntime` / `WorkflowRuntime` / HTTP 路由目前都是"无身份调用"——任何拿到 `AgentRuntime` 引用的代码都能对任意 agent instance / session / workflow 发起 prompt、dispatch、invoke、reset。生产部署原则 7 明确"应用拥有业务权限；agent instance id 不代表授权"，本组件就是为该原则补上落地的 SPI 与扩展点。

---

## 实现状态（2026-07-05）

**✓ 已实现**。`HelmSecurityContext`/`HelmAuthorizer`/`HelmAction`（含 `TOOL_EXECUTE`/`MEMORY_WRITE`/`SANDBOX_COMMAND`）/`HelmResource`，`AgentRuntime` admission `authorize()`，HTTP `SecurityContextExtractor`（header extractor 现为 dev opt-in，不再默认自动装配）。

## 1. 背景与目标

### 1.1 为什么需要 Authorizer / SecurityContext

当前 Helm 在三个层面缺少身份与授权：

1. **Runtime 层**：`AgentRuntime.prompt` / `dispatch` / `resetSession` / `listSessions` / `getOperation` 等公开方法只接收 `AgentPromptRequest` / 字符串 id，没有调用方身份。任何同进程代码都能读取或修改他人 session。
2. **HTTP 层**：`HelmHttpRouter`（`helm-http-core/src/main/java/io/agent/helm/http/core/HelmHttpRouter.java:26`）只做 method+path 匹配，不提取 principal，不在路由前授权。Servlet 适配器同样只是把 `HttpServletRequest` 翻译成 `HelmHttpRequest`（`helm-http-servlet/src/main/java/io/agent/helm/http/servlet/HelmHttpServlet.java:30`）。
3. **请求 DTO 层**：`AgentPromptRequest(agentName, instanceId, sessionName, text)`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentPromptRequest.java:3`）与 `WorkflowInvokeRequest(workflowName, input)`（`helm-runtime/src/main/java/io/agent/helm/runtime/WorkflowInvokeRequest.java:3`）都没有 principal/tenant 字段。

设计原则 7（`docs/roadmap.md:32`）要求"Helm 提供 authorizer/security context 扩展点；agent instance id 不代表授权"，第 3.1 节（`docs/roadmap.md:84`）也把"authorizer 落地（M6）"列为待补生产能力。M6 交付清单（`docs/roadmap.md:290-292`）明确列出 `HelmSecurityContext`、`HelmAuthorizer`、request body size/depth/timeouts，验收项（`docs/roadmap.md:296-300`）要求"每个 route 执行前可授权；error response 只含 code + safe details"。

### 1.2 本组件目标

| 目标 | 内容 |
| --- | --- |
| 定义 `HelmSecurityContext` | 不可变值类型，承载 principal / roles / 业务属性 / 请求级 metadata。放 `helm-core` 的 `core.security` 包。 |
| 定义 `HelmAuthorizer` SPI | `AuthorizationResult authorize(HelmSecurityContext, HelmAction, HelmResource)`，可抛 `AuthorizationException`。放 `helm-core` 的 `core.security` 包。 |
| 定义 `HelmAction` 枚举 | 对齐 `AgentRuntime` / `WorkflowRuntime` 既有公开方法（prompt / dispatch / workflow invoke / inspection / session 管理）。 |
| 定义 `HelmResource` | `record(type, name, attributes)`，描述被授权对象（agent / session / operation / workflow run）。 |
| 定义 `AuthorizationException` | `HelmException` 子类，code 为 `UNAUTHORIZED` 或 `FORBIDDEN`，details 只含安全的 action/resource 标识。 |
| Runtime 集成 | `AgentRuntime.Builder.authorizer(...)` + per-request `HelmSecurityContext`；每个公开方法入口在 admission 阶段调用 authorizer。 |
| HTTP / Servlet 集成 | `SecurityContextExtractor` 从请求头提取 principal，构造 `HelmSecurityContext`，路由前调用 authorizer。失败映射 401/403。 |
| 默认策略 | 未配置 authorizer 时 allow-all（dev 友好，但日志 WARN）；HTTP 启用时文档明示风险。 |

### 1.3 不解决什么

- **不做认证（authentication）**：Helm 不实现 OAuth2 / OIDC / SAML。principal 由调用方（API gateway、Spring Security filter、Servlet `getUserPrincipal()`）或 `SecurityContextExtractor` 提取，Helm 只消费 principal 字符串。
- **不做 RBAC/ABAC 引擎**：`HelmAuthorizer` 是 SPI，具体策略（角色表、ACL、OPA）由应用实现。Helm 只提供 `AllowAllAuthorizer` 与 `DenyAllAuthorizer` 两个参考实现。
- **不做 tool 级授权**：tool 执行由 `AgentConfig` 的 tool 列表控制（agent 定义已收窄）；tool 内部副作用由 tool 自身负责。本组件只在 admission 决定"这个 principal 能否对这个 agent/session/workflow 发起这个动作"。
- **不做 HTTP 传输安全**：TLS 终止、mTLS 由部署层负责。
- **不做 request body size / depth / timeouts 的完整实现**：M6 交付清单提到这些，但它们是 HTTP 层独立的 hardening 项，本组件只规定它们与 authorizer 的执行顺序（admission 之后才解析 body）。

---

## 2. 现状与缺口

> **注**：以下缺口分析反映设计时的现状；当前实现状态见文首「实现状态（2026-07-05）」。

### 2.1 roadmap 出处

| 出处 | 内容 | 本组件解决位置 |
| --- | --- | --- |
| `docs/roadmap.md:32`（设计原则 7） | "应用拥有业务权限；Helm 提供 authorizer/security context 扩展点；agent instance id 不代表授权" | §3 全节 |
| `docs/roadmap.md:84`（§3.1 缺口） | "authorizer 落地（M6）"列为待补生产能力 | §3 全节 |
| `docs/roadmap.md:290-292`（M6 交付） | `HelmSecurityContext`、`HelmAuthorizer`、request body size/depth/timeouts | §3.2–§3.9；body 限制本组件只规定执行顺序，实现属 M6 HTTP hardening |
| `docs/roadmap.md:296-300`（M6 验收） | HTTP opt-in；每个 route 执行前可授权；error response 只含 code + safe details；`helm-http-core` 无 Servlet/Spring 依赖 | §7 |

### 2.2 代码现状与缺口

#### 2.2.1 Runtime 层

`AgentRuntime`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java`）公开方法：

| 方法 | 行号 | 当前授权 | 缺口 |
| --- | --- | --- | --- |
| `prompt(AgentPromptRequest)` | :78 | 无 | 入口需授权 `PROMPT` |
| `dispatch(AgentPromptRequest)` | :84 | 无 | 入口需授权 `DISPATCH` |
| `getOperation(String)` | :97 | 无 | 需授权 `READ_OPERATION` |
| `getOperationEvents(String)` | :101 | 无 | 需授权 `READ_OPERATION`（事件隶属 operation） |
| `listOperations()` | :105 | 无 | 需授权 `LIST_OPERATIONS` |
| `listSessions()` | :110 | 无 | 需授权 `LIST_SESSIONS` |
| `getSession(String)` | :115 | 无 | 需授权 `READ_SESSION` |
| `resetSession(String)` | :120 | 无 | 需授权 `DELETE_SESSION` |

`executePrompt`（:127-240）的执行序列：sessionId 计算 → `activeSessions` 锁定 → `store.saveOperation`（admission）→ event → agent resolve → session load → engine run → saveSession → saveOperation(success)。**当前 admission 在 `store.saveOperation`（:135）处，但没有授权检查插入点**。本组件在 `activeSessions` 锁定之前、`saveOperation` 之前插入授权检查（详见 §3.7）。

`AgentRuntime.Builder`（:379-425）当前字段：agents / providers / store / memoryStore / maxSessionMessages。**缺 `authorizer` 字段**。

`WorkflowRuntime`（`helm-runtime/src/main/java/io/agent/helm/runtime/WorkflowRuntime.java`）公开方法：

| 方法 | 行号 | 当前授权 | 缺口 |
| --- | --- | --- | --- |
| `invoke(WorkflowInvokeRequest)` | :44 | 无 | 入口需授权 `WORKFLOW_INVOKE` |
| `getRun(String)` | :115 | 无 | 需授权 `READ_OPERATION`（run 隶属 workflow） |
| `listRuns()` | :119 | 无 | 需授权 `LIST_OPERATIONS` |
| `getRunEvents(String)` | :123 | 无 | 需授权 `READ_OPERATION` |

`WorkflowRuntime.Builder`（:147-170）当前字段：workflows / providers / store。**缺 `authorizer` 字段**。

#### 2.2.2 请求 DTO 层

| DTO | 现状 | 缺口 |
| --- | --- | --- |
| `AgentPromptRequest`（`AgentPromptRequest.java:3`） | `record(agentName, instanceId, sessionName, text)` | 无 principal / securityContext |
| `WorkflowInvokeRequest`（`WorkflowInvokeRequest.java:3`） | `record(workflowName, input)` | 无 principal / securityContext |
| `AgentContext`（`helm-core/.../agent/AgentContext.java:3`） | `record(agentName, instanceId)` | 无 principal（agent 定义阶段） |

本组件**不改这些 DTO 的签名**（避免破坏既有调用方），而是新增 `SecurityContextAware` 重载方法或通过 `ThreadLocal` / 显式参数注入 per-request `HelmSecurityContext`（见 §3.7 决策）。

#### 2.2.3 HTTP 层

`HelmHttpRouter`（`helm-http-core/.../HelmHttpRouter.java:26-44`）的 `handle` 方法遍历 routes，匹配后直接调用 `compiled.route.handler().handle(routed)`，异常交给 `HttpErrors.toResponse(e)`。**没有 security context 提取与授权插点**。

`HelmHttpRoutes`（`helm-http-core/.../HelmHttpRoutes.java:28-130`）注册了 8 条 route（prompt / dispatch / workflow invoke / getOperation / getOperationEvents / sessionOperations / getRun / workflowRuns），每条 handler 直接调用 runtime 方法，**无授权**。

`HttpErrors.statusFor`（`HttpErrors.java:47-58`）当前 code→status 映射没有 `UNAUTHORIZED` / `FORBIDDEN`，**需补 401/403 映射**。

`HelmHttpServlet`（`helm-http-servlet/.../HelmHttpServlet.java:30-46`）只翻译 header/body，**不提取 principal**，也不利用 `HttpServletRequest.getUserPrincipal()`。

#### 2.2.4 缺口总结

| 缺口 | 影响 | 本组件解决 |
| --- | --- | --- |
| 无 `HelmSecurityContext` 类型 | 调用方无法表达身份 | §3.2 |
| 无 `HelmAuthorizer` SPI | 应用无法插入授权策略 | §3.5 |
| 无 `HelmAction` / `HelmResource` | authorizer 无标准输入 | §3.3 / §3.4 |
| `AgentRuntime` / `WorkflowRuntime` 入口无授权插点 | 任意调用方越权 | §3.7 |
| HTTP 路由前无授权 | 任意 HTTP 调用方越权 | §3.8 |
| Servlet 不提取 principal | 标准 Servlet 认证结果无法传递 | §3.9 |
| `HttpErrors` 无 401/403 映射 | 授权失败返回 500 | §3.6 / §3.8 |
| `ErrorCode` 注册表无 `UNAUTHORIZED` / `FORBIDDEN` | 授权错误 code 不稳定 | §3.6（与组件 #11 协同） |

---

## 3. 设计方案

### 3.1 模块归属与依赖

| 类型 | 模块 | 包 | 档位 |
| --- | --- | --- | --- |
| `HelmSecurityContext` | `helm-core` | `io.agent.helm.core.security` | Public API |
| `HelmAction` | `helm-core` | `io.agent.helm.core.security` | Public API（enum） |
| `HelmResource` | `helm-core` | `io.agent.helm.core.security` | Public API（record） |
| `HelmAuthorizer` | `helm-core` | `io.agent.helm.core.security` | SPI |
| `AuthorizationResult` | `helm-core` | `io.agent.helm.core.security` | Public API（record） |
| `AuthorizationException` | `helm-core` | `io.agent.helm.core.error` | Public（HelmException 子类） |
| `AllowAllAuthorizer` / `DenyAllAuthorizer` | `helm-runtime` | `io.agent.helm.runtime.security` | Public（参考实现） |
| `SecurityContextExtractor` | `helm-http-core` | `io.agent.helm.http.core.security` | SPI |
| `HeaderSecurityContextExtractor` | `helm-http-core` | `io.agent.helm.http.core.security` | Public（默认实现） |
| `ServletSecurityContextExtractor` | `helm-http-servlet` | `io.agent.helm.http.servlet.security` | Public（Servlet 适配） |

依赖守则校验：

- `helm-core` 新增 `core.security` 包，**只依赖 JDK**（无 Spring/Servlet/JDBC/SDK/logging），符合 Core-first。
- `helm-runtime` 新增 `runtime.security` 包，依赖 `helm-core`，符合既有依赖图。
- `helm-http-core` 新增 `http.core.security` 包，依赖 `helm-core`（消费 `HelmSecurityContext` / `HelmAuthorizer`），不依赖 Servlet/Spring。
- `helm-http-servlet` 依赖 `helm-http-core` + Jakarta Servlet API（既有），新增 `servlet.security` 包。

### 3.2 HelmSecurityContext

```java
package io.agent.helm.core.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable identity and context for an authorization decision. A {@code HelmSecurityContext} describes
 * <em>who</em> is calling (principal), <em>what roles</em> they hold, <em>what business attributes</em> apply
 * (e.g. tenantId, region), and <em>request-level metadata</em> (e.g. trace id) for correlation.
 *
 * <p>This type intentionally carries no credentials. Authentication is the caller's responsibility (API gateway,
 * Servlet filter, Spring Security). Helm only consumes the resulting principal string.
 *
 * <p>{@code principal} must not be treated as an authorization grant by itself (design principle 7).
 *
 * @since 0.2.0
 */
public record HelmSecurityContext(
        String principal,
        Set<String> roles,
        Map<String, Object> attributes,
        Map<String, Object> metadata) {

    public HelmSecurityContext {
        Objects.requireNonNull(principal, "principal");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    /** Creates a context for an anonymous/unauthenticated caller. Principal is the empty string. */
    public static HelmSecurityContext anonymous() {
        return new HelmSecurityContext("", Set.of(), Map.of(), Map.of());
    }

    public boolean isAnonymous() {
        return principal.isEmpty();
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String name) {
        return (T) attributes.get(name);
    }
}
```

字段语义约束：

| 字段 | 类型 | 内容 | 示例 |
| --- | --- | --- | --- |
| `principal` | `String` | 用户或服务标识。可为空字符串（匿名）。不携带凭据。 | `"user-42"` / `"svc-billing"` |
| `roles` | `Set<String>` | 角色集合，由调用方/`SecurityContextExtractor` 填充。Helm 不解释角色语义。 | `Set.of("agent.support.agent")` |
| `attributes` | `Map<String,Object>` | 业务属性，用于 authorizer 决策。 | `{"tenantId":"acme","region":"eu-west"}` |
| `metadata` | `Map<String,Object>` | 请求级 trace/correlation 信息，**不参与授权决策**，仅用于日志关联。 | `{"traceId":"abc","requestId":"req-1"}` |

`metadata` 与 `attributes` 分离的原因：authorizer 实现可能误把 trace id 当授权依据，分离后语义清晰，且 `metadata` 可安全进日志（`attributes` 可能含业务敏感字段）。

### 3.3 HelmAction

```java
package io.agent.helm.core.security;

/**
 * Stable action codes for authorization decisions. Each value aligns with a public method on
 * {@code AgentRuntime} or {@code WorkflowRuntime} so that an authorizer can decide per-entry-point.
 *
 * @since 0.2.0
 */
public enum HelmAction {
    /** {@code AgentRuntime.prompt}. */
    PROMPT,

    /** {@code AgentRuntime.dispatch}. */
    DISPATCH,

    /** {@code WorkflowRuntime.invoke}. */
    WORKFLOW_INVOKE,

    /** {@code AgentRuntime.getOperation} / {@code getOperationEvents} / {@code WorkflowRuntime.getRun} / {@code getRunEvents}. */
    READ_OPERATION,

    /** {@code AgentRuntime.listOperations} / {@code WorkflowRuntime.listRuns}. */
    LIST_OPERATIONS,

    /** {@code AgentRuntime.getSession}. */
    READ_SESSION,

    /** {@code AgentRuntime.listSessions}. */
    LIST_SESSIONS,

    /** {@code AgentRuntime.resetSession}. */
    DELETE_SESSION,

    /** Read events for an operation or workflow run. Subsumed by {@link #READ_OPERATION} when the caller already holds it; separate code for audit clarity. */
    READ_EVENTS;
}
```

设计决策：

- **不拆 `READ_OPERATION` 与 `READ_RUN`**：两者都是"读取 inspection 记录"，authorizer 通常一视同仁。`READ_EVENTS` 单独列出以便审计区分（事件可能含更多脱敏后的内容摘要）。
- **`DISPATCH` 与 `PROMPT` 分开**：dispatch 是 admission-only（不阻塞执行就是排队），prompt 是同步执行；策略上 dispatch 可能更宽松（只创建记录）也可能更严（防止单次 dispatch 滥用）。分开让应用决定。
- **不引入 `EXECUTE_TOOL` / `CALL_MODEL`**：tool/model 层授权属 engine hardening（组件 #2）与 `AgentConfig` 收窄，不在本组件范围。

### 3.4 HelmResource

```java
package io.agent.helm.core.security;

import java.util.Map;
import java.util.Objects;

/**
 * The resource an action targets. {@code type} is a stable string (not an enum) so applications can introduce
 * custom resource types (e.g. {@code "tenant"}) without core changes. Built-in types are listed below.
 *
 * <p>Built-in resource types:
 * <ul>
 *   <li>{@code AGENT} — {@code name} is the agent name; {@code attributes} may carry {@code instanceId}, {@code sessionName}.</li>
 *   <li>{@code SESSION} — {@code name} is the session id ({@code agentName:instanceId:sessionName}); used for session inspection/reset.</li>
 *   <li>{@code OPERATION} — {@code name} is the operation id; used for operation inspection.</li>
 *   <li>{@code WORKFLOW} — {@code name} is the workflow name; {@code attributes} may carry {@code runId}.</li>
 *   <li>{@code WORKFLOW_RUN} — {@code name} is the run id; used for run inspection.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public record HelmResource(String type, String name, Map<String, Object> attributes) {

    public HelmResource {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_SESSION = "SESSION";
    public static final String TYPE_OPERATION = "OPERATION";
    public static final String TYPE_WORKFLOW = "WORKFLOW";
    public static final String TYPE_WORKFLOW_RUN = "WORKFLOW_RUN";

    public static HelmResource agent(String agentName, Map<String, Object> attributes) {
        return new HelmResource(TYPE_AGENT, agentName, attributes);
    }

    public static HelmResource session(String sessionId) {
        return new HelmResource(TYPE_SESSION, sessionId, Map.of());
    }

    public static HelmResource operation(String operationId) {
        return new HelmResource(TYPE_OPERATION, operationId, Map.of());
    }

    public static HelmResource workflow(String workflowName, Map<String, Object> attributes) {
        return new HelmResource(TYPE_WORKFLOW, workflowName, attributes);
    }

    public static HelmResource workflowRun(String runId) {
        return new HelmResource(TYPE_WORKFLOW_RUN, runId, Map.of());
    }
}
```

`type` 用字符串常量而非 enum，理由：

- 应用可扩展自定义资源类型（如 `TENANT`、`SKILL`）而不需要改 core enum。
- authorizer 实现可对未知 type 返回 deny（保守默认）。
- 与 `HelmAction` 不同：action 是固定枚举（runtime 方法集合稳定），resource type 是开放集合。

### 3.5 HelmAuthorizer SPI 与 AuthorizationResult

```java
package io.agent.helm.core.security;

/**
 * Authorization decision returned by {@link HelmAuthorizer#authorize}. {@link #ALLOWED} is the constant for an
 * affirmative decision; a denied decision carries a human-readable {@code reason} (safe to expose in error
 * details) and an optional {@code developerReason} (not exposed to HTTP callers).
 *
 * @since 0.2.0
 */
public record AuthorizationResult(Decision decision, String reason, String developerReason) {

    public enum Decision { ALLOWED, DENIED }

    public static final AuthorizationResult ALLOWED = new AuthorizationResult(Decision.ALLOWED, "", "");

    public static AuthorizationResult denied(String reason) {
        return new AuthorizationResult(Decision.DENIED, reason == null ? "" : reason, "");
    }

    public static AuthorizationResult denied(String reason, String developerReason) {
        return new AuthorizationResult(Decision.DENIED, reason == null ? "" : reason, developerReason == null ? "" : developerReason);
    }

    public boolean isAllowed() {
        return decision == Decision.ALLOWED;
    }
}
```

```java
package io.agent.helm.core.security;

/**
 * SPI for application-provided authorization. Implementations decide whether a {@link HelmSecurityContext} may
 * perform a {@link HelmAction} on a {@link HelmResource}.
 *
 * <p>Implementations <b>must not</b> mutate the security context, the resource, or any runtime state. They should
 * be side-effect free and fast (sub-millisecond) because they run on every public runtime entry point.
 *
 * <p>When in doubt, deny. Helm's default {@code AllowAllAuthorizer} (in {@code helm-runtime}) is for development
 * only and logs a {@code WARN} when used.
 *
 * <p>Throwing {@link io.agent.helm.core.error.AuthorizationException} is equivalent to returning a denied result
 * but lets the implementor attach structured details (action/resource). Either style is supported.
 *
 * @since 0.2.0
 */
public interface HelmAuthorizer {

    /**
     * Decides whether {@code ctx} may perform {@code action} on {@code resource}.
     *
     * @param ctx the caller identity; never {@code null}, but may be {@link HelmSecurityContext#anonymous()}
     * @param action the action being attempted; never {@code null}
     * @param resource the target resource; never {@code null}
     * @return the decision; never {@code null}
     * @throws io.agent.helm.core.error.AuthorizationException to deny with structured details
     */
    AuthorizationResult authorize(HelmSecurityContext ctx, HelmAction action, HelmResource resource);
}
```

设计决策：

- **返回 `AuthorizationResult` 而非 boolean**：boolean 无法携带拒绝原因，调用方只能拿到 `false`。`AuthorizationResult` 的 `reason` 可安全暴露给 HTTP 调用方（进 `details`），`developerReason` 不暴露。
- **允许抛 `AuthorizationException`**：与既有 `HelmException` 风格一致（如 `SessionBusyException`、`ToolExecutionException`），authorizer 可选择抛异常或返回 denied result。runtime 入口对两种风格统一处理（详见 §3.7）。
- **同步 SPI**：M6 不引入异步授权（CompletableFuture）。异步授权属 durable scale（组件 #9）的 admission 队列，不在本组件。
- **不传 `Map<String,Object> context`**：所有上下文已在 `HelmSecurityContext` 与 `HelmResource` 里，避免 SPI 多一个松散参数。

### 3.6 AuthorizationException 与 ErrorCode 注册

新增 `AuthorizationException`（`helm-core/src/main/java/io/agent/helm/core/error/AuthorizationException.java`）：

```java
package io.agent.helm.core.error;

import java.util.Map;

/**
 * Thrown when a {@link io.agent.helm.core.security.HelmAuthorizer} denies an action, or when no security context
 * is present for a runtime that requires one.
 *
 * <p>{@code details} contains only safe identifiers: {@code action}, {@code resourceType}, {@code resourceName}.
 * It must not contain the principal or any {@code HelmSecurityContext.attributes} value.
 *
 * @since 0.2.0
 */
public final class AuthorizationException extends HelmException {

    /** Use when the caller is not authenticated (no principal, or principal is anonymous). */
    public static final String CODE_UNAUTHENTICATED = "UNAUTHORIZED";

    /** Use when the caller is authenticated but lacks permission. */
    public static final String CODE_FORBIDDEN = "FORBIDDEN";

    public AuthorizationException(String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
        if (!CODE_UNAUTHENTICATED.equals(code) && !CODE_FORBIDDEN.equals(code)) {
            throw new IllegalArgumentException("AuthorizationException code must be UNAUTHORIZED or FORBIDDEN, got: " + code);
        }
    }

    /** Convenience factory for a forbidden decision from an {@link io.agent.helm.core.security.AuthorizationResult}. */
    public static AuthorizationException forbidden(
            io.agent.helm.core.security.HelmAction action,
            io.agent.helm.core.security.HelmResource resource,
            String reason,
            String developerReason) {
        return new AuthorizationException(
                CODE_FORBIDDEN,
                reason == null || reason.isBlank() ? "forbidden" : reason,
                Map.of(
                        "action", action.name(),
                        "resourceType", resource.type(),
                        "resourceName", resource.name()),
                developerReason == null ? Map.of() : Map.of("developerReason", developerReason));
    }

    /** Convenience factory for an unauthenticated caller. */
    public static AuthorizationException unauthenticated(String reason) {
        return new AuthorizationException(
                CODE_UNAUTHENTICATED,
                reason == null || reason.isBlank() ? "unauthenticated" : reason,
                Map.of(),
                Map.of());
    }
}
```

`HttpErrors.statusFor`（`helm-http-core/.../HttpErrors.java:47`）补 401/403 映射：

```java
public static int statusFor(String code) {
    return switch (code) {
        case "AGENT_NOT_FOUND", "WORKFLOW_NOT_FOUND" -> 404;
        case "VALIDATION_FAILED" -> 400;
        case "UNAUTHORIZED" -> 401;        // 新增
        case "FORBIDDEN" -> 403;            // 新增
        case "SESSION_BUSY" -> 409;
        case "CONTEXT_OVERFLOW" -> 413;
        case "PROVIDER_RATE_LIMITED" -> 429;
        case "PROVIDER_TIMEOUT" -> 504;
        case "PROVIDER_NOT_FOUND", "PROVIDER_ERROR" -> 502;
        default -> 500;
    };
}
```

`ErrorCode` 注册表（与组件 #11 协同）：在 `ErrorCode` 枚举追加 `UNAUTHORIZED`、`FORBIDDEN`，并在 `docs/contracts/error-codes.md` 登记。code 命名遵循 SCREAMING_SNAKE_CASE，无 `_ERROR`/`_FAILED` 后缀（与 `SESSION_BUSY`、`AGENT_NOT_FOUND` 风格一致）。

`details` 字段白名单（对齐组件 #11 §3.3.3）：

| 允许放入 details | 禁止放入 details |
| --- | --- |
| `action`（枚举名）、`resourceType`、`resourceName` | `principal`、`roles`、`attributes` 内容 |
| `reason`（authorizer 提供的安全摘要） | 完整 `HelmSecurityContext` 序列化 |
| `errorCode` | 内部 ACL 规则 id（除非显式可暴露） |

### 3.7 AgentRuntime / WorkflowRuntime 集成

#### 3.7.1 per-request SecurityContext 注入策略

三个候选方案：

| 方案 | 形态 | 优点 | 缺点 | 决策 |
| --- | --- | --- | --- | --- |
| A. 改 `AgentPromptRequest` 加字段 | `record(agentName, instanceId, sessionName, text, securityContext)` | 显式 | 破坏既有 record 签名，所有调用方改 | 不选 |
| B. 新增重载方法 | `prompt(AgentPromptRequest, HelmSecurityContext)` | 既有方法不变 | API 表面翻倍 | 不选 |
| C. per-runtime default + per-request override | Builder 设默认 ctx；方法提供 `*WithSecurity` 重载 | 既有兼容；HTTP/Servlet 用 override；CLI 用 default | 调用方需知道何时传 override | **选 C** |

选 C 的理由：

- 既有 `prompt(AgentPromptRequest)` 不变，内部用 `defaultSecurityContext`（Builder 设置，可为 `anonymous()`）。
- 新增 `prompt(AgentPromptRequest, HelmSecurityContext)` 显式 override；HTTP/Servlet 永远走 override 路径。
- `dispatch` / `resetSession` / `getSession` / `listSessions` / `getOperation` / `getOperationEvents` / `listOperations` 同理新增 `*WithSecurity` 重载。
- `WorkflowRuntime.invoke` / `getRun` / `listRuns` / `getRunEvents` 同理。
- 既有 `*WithSecurity` 调用统一委托到内部 `authorize(action, resource, ctx)` 方法；既有方法委托时用 `defaultSecurityContext`。

#### 3.7.2 AgentRuntime 改动

Builder 新增字段：

```java
public static final class Builder {
    private final List<AgentDefinition> agents = new ArrayList<>();
    private final List<ModelProvider> providers = new ArrayList<>();
    private RuntimeStore store = new InMemoryRuntimeStore();
    private MemoryStore memoryStore;
    private int maxSessionMessages;
    private HelmAuthorizer authorizer;                          // 新增；null 表示 allow-all + WARN
    private HelmSecurityContext defaultSecurityContext = HelmSecurityContext.anonymous();  // 新增

    public Builder authorizer(HelmAuthorizer authorizer) {
        this.authorizer = authorizer;
        return this;
    }

    public Builder defaultSecurityContext(HelmSecurityContext ctx) {
        this.defaultSecurityContext = Objects.requireNonNull(ctx, "defaultSecurityContext");
        return this;
    }
    // ... build() 传入 authorizer 与 defaultSecurityContext
}
```

公开方法新增 `*WithSecurity` 重载（以 `prompt` 为例）：

```java
public PromptResult prompt(AgentPromptRequest request) {
    return prompt(request, defaultSecurityContext);
}

public PromptResult prompt(AgentPromptRequest request, HelmSecurityContext ctx) {
    String operationId = "op_" + UUID.randomUUID();
    PromptExecution execution = executePrompt(request, operationId, ctx);
    return new PromptResult(execution.operationId(), execution.text());
}
```

`dispatch` / `resetSession` / `getSession` / `listSessions` / `getOperation` / `getOperationEvents` / `listOperations` 同理新增 `*WithSecurity` 重载，既有方法委托。

#### 3.7.3 授权插入点：admission 阶段

`executePrompt`（当前 `AgentRuntime.java:127-240`）改造后序列：

```text
executePrompt(request, operationId, ctx):
  1. sessionId = sessionId(...)                                 // 既有
  2. authorize(PROMPT, HelmResource.agent(agentName, {instanceId, sessionName}), ctx)  // 新增：admission 授权
       -> 失败：抛 AuthorizationException，不 saveOperation、不 appendEvent、不入 activeSessions
  3. activeSessions.putIfAbsent(sessionId, TRUE)                 // 既有（:129）
  4. store.saveOperation(running)                               // 既有 admission（:135）
  5. appendEvent(OPERATION_STARTED, redact)                     // 既有（:145）
  6. agent resolve / session load / engine run / saveSession    // 既有
  7. store.saveOperation(succeeded)                            // 既有
  8. appendEventSafely(OPERATION_SUCCEEDED)                     // 既有
  9. finally: activeSessions.remove(sessionId)                  // 既有
```

关键决策：**授权在 admission（`saveOperation`）之前**。理由：

- 设计原则 4"Admission 优先"：prompt/dispatch/workflow invoke 应先形成可检查记录再执行。但授权是更前置的过滤——未授权的请求**不应产生 operation 记录**，否则攻击者可通过枚举 operation id 探测资源存在性（信息泄漏）。
- 未授权请求**不进 `activeSessions` 锁**，避免 DoS 攻击者用未授权请求占满 session 锁。
- 未授权请求**不 appendEvent**，避免事件日志被未授权噪声淹没。

`dispatch` 与 `prompt` 共用 `executePrompt`，授权检查在 `executePrompt` 入口，两者都覆盖。

#### 3.7.4 inspection / session 方法的授权

`getOperation` / `getOperationEvents` / `listOperations` / `getSession` / `listSessions` / `resetSession` 的 `*WithSecurity` 重载在方法入口直接调用 `authorize`：

```java
public Optional<OperationRecord> getOperation(String operationId) {
    return getOperation(operationId, defaultSecurityContext);
}

public Optional<OperationRecord> getOperation(String operationId, HelmSecurityContext ctx) {
    authorize(READ_OPERATION, HelmResource.operation(operationId), ctx);
    return store.loadOperation(operationId);
}

public void resetSession(String sessionId, HelmSecurityContext ctx) {
    authorize(DELETE_SESSION, HelmResource.session(sessionId), ctx);
    if (activeSessions.containsKey(sessionId)) {
        throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
    }
    store.deleteSession(sessionId);
}
```

注意：inspection 方法的授权在 **runtime 层只校验 action + resource 标识**。runtime 不校验"这个 operation 是否属于这个 principal"——那是应用 authorizer 的职责（可查 store 关联 session→agent→tenant）。Helm 只保证"调用了 authorizer"。

#### 3.7.5 内部 authorize 方法

```java
private void authorize(HelmAction action, HelmResource resource, HelmSecurityContext ctx) {
    HelmAuthorizer authz = authorizer;       // may be null
    if (authz == null) {
        // dev 默认：allow-all，但首次调用记 WARN
        warnIfAllowAll(action);
        return;
    }
    AuthorizationResult result = authz.authorize(ctx, action, resource);
    if (!result.isAllowed()) {
        throw AuthorizationException.forbidden(action, resource, result.reason(), result.developerReason());
    }
}
```

`warnIfAllowAll` 用 `java.util.logging.Logger`（JDK 内置，core 不引依赖；runtime 已有 `EventRedactor` 等 JDK 用法）。首次调用记一条 WARN，避免每个请求都刷日志。

#### 3.7.6 WorkflowRuntime 改动

`WorkflowRuntime.Builder` 同样新增 `authorizer(HelmAuthorizer)` 与 `defaultSecurityContext`。`invoke` 入口授权 `WORKFLOW_INVOKE` + `HelmResource.workflow(workflowName, Map.of())`；`getRun` / `listRuns` / `getRunEvents` 授权 `READ_OPERATION` / `LIST_OPERATIONS` / `READ_OPERATION`。

注意：`WorkflowRuntime.invoke` 内部构造了临时 `AgentRuntime`（`WorkflowRuntime.java:65-70`）。该内部 `AgentRuntime` **继承外层 `WorkflowRuntime` 的 authorizer 与 defaultSecurityContext**，使 workflow 内的 `harness().session(...).prompt(...)` 调用也走授权（用 workflow 的 security context）。

### 3.8 HTTP 集成

#### 3.8.1 SecurityContextExtractor SPI

```java
package io.agent.helm.http.core.security;

import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.http.core.HelmHttpRequest;

/**
 * Extracts a {@link HelmSecurityContext} from a framework-neutral {@link HelmHttpRequest}. Implementations
 * read headers (e.g. {@code Authorization}, {@code X-Helm-Principal}, {@code X-Helm-Tenant}) and produce
 * an immutable context. Returning {@code HelmSecurityContext#anonymous()} is allowed and triggers
 * {@code UNAUTHORIZED} when an authorizer is configured.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface SecurityContextExtractor {
    HelmSecurityContext extract(HelmHttpRequest request);
}
```

#### 3.8.2 默认实现：HeaderSecurityContextExtractor

```java
package io.agent.helm.http.core.security;

import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.http.core.HelmHttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Default extractor that reads Helm-defined headers. It does <b>not</b> validate tokens — it only copies the
 * principal string supplied by an upstream gateway/filter. Token validation is the gateway's job.
 *
 * <p>Headers consumed:
 * <ul>
 *   <li>{@code X-Helm-Principal} — principal string. If absent, falls back to anonymous.</li>
 *   <li>{@code X-Helm-Roles} — comma-separated role list.</li>
 *   <li>{@code X-Helm-Tenant} — copied into {@code attributes.tenantId}.</li>
 *   <li>{@code X-Helm-Trace-Id} — copied into {@code metadata.traceId}.</li>
 * </ul>
 *
 * <p>{@code Authorization: Bearer ...} is intentionally not parsed here; Helm does not do OAuth2. If a deployment
 * wants bearer-token→principal mapping, it provides a custom {@link SecurityContextExtractor}.
 */
public final class HeaderSecurityContextExtractor implements SecurityContextExtractor {

    public static final String HEADER_PRINCIPAL = "X-Helm-Principal";
    public static final String HEADER_ROLES = "X-Helm-Roles";
    public static final String HEADER_TENANT = "X-Helm-Tenant";
    public static final String HEADER_TRACE_ID = "X-Helm-Trace-Id";

    @Override
    public HelmSecurityContext extract(HelmHttpRequest request) {
        String principal = request.header(HEADER_PRINCIPAL);
        if (principal == null || principal.isBlank()) {
            return HelmSecurityContext.anonymous();
        }
        String rolesHeader = request.header(HEADER_ROLES);
        Set<String> roles = rolesHeader == null || rolesHeader.isBlank()
                ? Set.of()
                : Set.of(rolesHeader.split(","));
        Map<String, Object> attributes = new LinkedHashMap<>();
        String tenant = request.header(HEADER_TENANT);
        if (tenant != null && !tenant.isBlank()) {
            attributes.put("tenantId", tenant);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        String traceId = request.header(HEADER_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            metadata.put("traceId", traceId);
        }
        return new HelmSecurityContext(principal.trim(), roles, attributes, metadata);
    }
}
```

#### 3.8.3 授权路由：AuthorizedHelmHttpRouter

不改 `HelmHttpRouter` 本身（保持单一职责：method+path 匹配）。新增装饰器：

```java
package io.agent.helm.http.core.security;

import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.security.HelmResource;
import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.http.core.HelmHttpRequest;
import io.agent.helm.http.core.HelmHttpResponse;
import io.agent.helm.http.core.HttpErrors;
import io.agent.helm.http.core.HelmHttpRouter;
import java.util.Map;

/**
 * Wraps a {@link HelmHttpRouter} so that every matched route is authorized against a {@link HelmAuthorizer}
 * before the handler runs. The action and resource are derived from the matched route + path params by a
 * pluggable {@link RouteAuthorizer}.
 */
public final class AuthorizedHelmHttpRouter {

    private final HelmHttpRouter delegate;
    private final SecurityContextExtractor extractor;
    private final RouteAuthorizer routeAuthorizer;

    public AuthorizedHelmHttpRouter(HelmHttpRouter delegate, SecurityContextExtractor extractor, RouteAuthorizer routeAuthorizer) {
        this.delegate = delegate;
        this.extractor = extractor;
        this.routeAuthorizer = routeAuthorizer;
    }

    public HelmHttpResponse handle(HelmHttpRequest request) {
        HelmSecurityContext ctx = extractor.extract(request);
        // Route first to learn matched path params (so RouteAuthorizer can build resource from {agent}/{instance}/...).
        AuthorizedDispatch dispatch = routeAuthorizer.resolve(request, ctx);
        if (dispatch != null && !dispatch.authorize()) {
            // Already denied inside resolve(); the exception is captured here.
            // (resolve() throws AuthorizationException to keep the SPI simple.)
        }
        try {
            // Pass ctx to the handler via a request attribute the handler reads.
            HelmHttpRequest withCtx = request.withSecurityContext(ctx);
            return delegate.handle(withCtx);
        } catch (AuthorizationException e) {
            return HttpErrors.toResponse(e);
        }
    }
}
```

`RouteAuthorizer` 把 (method, path, pathParams, ctx) 映射到 `(HelmAction, HelmResource)` 并调用 authorizer：

```java
@FunctionalInterface
public interface RouteAuthorizer {
    /**
     * Resolve the action/resource for the matched route and authorize. Throws {@link AuthorizationException}
     * on denial. Returning normally means allowed.
     */
    void authorize(HelmHttpRequest matched, HelmSecurityContext ctx);
}
```

实现策略：`HelmHttpRoutes` 在构建 router 时同时构建一个 `RouteAuthorizer`，每条 route 关联一个 `(method, pattern) → (action, resourceBuilder)` 条目。例如：

| Route | Action | Resource |
| --- | --- | --- |
| `POST /agents/{agent}/instances/{instance}/sessions/{session}/prompt` | `PROMPT` | `agent(agent, {instanceId:instance, sessionName:session})` |
| `POST /agents/{agent}/dispatch` | `DISPATCH` | `agent(agent, Map.of())` |
| `POST /workflows/{workflow}/invoke` | `WORKFLOW_INVOKE` | `workflow(workflow, Map.of())` |
| `GET /operations/{id}` | `READ_OPERATION` | `operation(id)` |
| `GET /operations/{id}/events` | `READ_EVENTS` | `operation(id)` |
| `GET /sessions/{id}/operations` | `LIST_OPERATIONS` | `session(id)` |
| `GET /workflow-runs/{id}` | `READ_OPERATION` | `workflowRun(id)` |
| `GET /workflows/{workflow}/runs` | `LIST_OPERATIONS` | `workflow(workflow, Map.of())` |

`RouteAuthorizer` 在 `delegate.handle` 之前调用——先做 method+path 匹配（拿到 pathParams），再授权，再执行 handler。若 route 不匹配（404），`RouteAuthorizer.resolve` 返回 null，直接走 delegate 返回 404，不触发授权（避免未匹配路径泄漏 authorizer 行为）。

handler 通过 `request.securityContext()` 读取 ctx，传给 runtime 的 `*WithSecurity` 重载：

```java
static HelmHttpHandler promptHandler(AgentRuntime runtime) {
    return request -> {
        HelmSecurityContext ctx = request.securityContext();   // 新增
        String text = readField(request.body(), "text");
        PromptResult result = runtime.prompt(
                new AgentPromptRequest(request.pathParam("agent"), request.pathParam("instance"),
                        request.pathParam("session"), text),
                ctx);                                            // 传 ctx
        // ...
    };
}
```

`HelmHttpRequest` 新增 `securityContext` 字段与 `withSecurityContext` 工厂（不可变 record，复制时保留）。默认值为 `HelmSecurityContext.anonymous()`，使未配置授权的 router 仍能工作。

#### 3.8.4 HTTP 错误映射

授权失败的两条路径：

1. **`SecurityContextExtractor` 返回 anonymous 且 authorizer 配置**：`RouteAuthorizer` 检测 anonymous + authorizer != null → 抛 `AuthorizationException.unauthenticated("anonymous caller")` → `HttpErrors.toResponse` → 401 + `{"error":{"code":"UNAUTHORIZED","message":"anonymous caller","details":{}}}`。
2. **`HelmAuthorizer.authorize` 返回 denied 或抛 `AuthorizationException.forbidden`**：`HttpErrors.toResponse` → 403 + `{"error":{"code":"FORBIDDEN","message":"...","details":{"action":"PROMPT","resourceType":"AGENT","resourceName":"support"}}}`。

`HttpErrors.toResponse` 既有逻辑（`HttpErrors.java:19-28`）已能处理 `HelmException` 子类，只需 `statusFor` 补 401/403 映射（§3.6）。`developerDetails` 与 `message` 不暴露（既有逻辑已只用 `code` + `details`）。

### 3.9 Servlet 集成

`HelmHttpServlet`（`helm-http-servlet/.../HelmHttpServlet.java:30-46`）当前直接调用 `router.handle`。改造点：

1. `HelmHttpServlet` 持有可选的 `ServletSecurityContextExtractor`，在构造 `HelmHttpRequest` 时调用它，把结果放入 `HelmHttpRequest.securityContext`。
2. 若未配置 extractor，servlet 用 `HttpServletRequest.getUserPrincipal()` 兜底：

```java
package io.agent.helm.http.servlet.security;

import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.http.core.security.SecurityContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import java.util.Set;

/**
 * Servlet-aware extractor: prefers an explicit {@link SecurityContextExtractor} for header-based flows;
 * falls back to {@link HttpServletRequest#getUserPrincipal()} (Servlet container managed security).
 */
public final class ServletSecurityContextExtractor {

    private final SecurityContextExtractor headerExtractor;

    public ServletSecurityContextExtractor() {
        this(new io.agent.helm.http.core.security.HeaderSecurityContextExtractor());
    }

    public ServletSecurityContextExtractor(SecurityContextExtractor headerExtractor) {
        this.headerExtractor = headerExtractor;
    }

    public HelmSecurityContext extract(HttpServletRequest req, Map<String, java.util.List<String>> headers) {
        // 1. 先走 header extractor（与 framework-neutral 一致）
        HelmSecurityContext fromHeaders = headerExtractor.extract(
                new io.agent.helm.http.core.HelmHttpRequest(
                        req.getMethod(), req.getRequestURI(), Map.of(), headers, ""));
        if (!fromHeaders.isAnonymous()) {
            return fromHeaders;
        }
        // 2. 兜底：Servlet container 认证（mTLS、BASIC、FORM 等）
        Principal p = req.getUserPrincipal();
        if (p != null && p.getName() != null && !p.getName().isBlank()) {
            java.util.Set<String> roles = new java.util.HashSet<>();
            for (String role : new String[]{"admin", "operator", "viewer"}) {  // 示例；实际由应用声明
                if (req.isUserInRole(role)) {
                    roles.add(role);
                }
            }
            return new HelmSecurityContext(p.getName(), Set.copyOf(roles), Map.of(), Map.of());
        }
        return HelmSecurityContext.anonymous();
    }
}
```

`HelmHttpServlet.service` 改造后：

```java
@Override
protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Map<String, List<String>> headers = collectHeaders(req);
    HelmSecurityContext ctx = extractor == null
            ? HelmSecurityContext.anonymous()
            : extractor.extract(req, headers);
    String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    HelmHttpRequest request = new HelmHttpRequest(
            req.getMethod(), req.getRequestURI(), Map.of(), headers, body, ctx);  // 新增 ctx 参数
    HelmHttpResponse response = router.handle(request);
    write(resp, response);
}
```

`HelmHttpServlet` 构造器新增重载 `HelmHttpServlet(router, extractor)`；既有 `HelmHttpServlet(router)` 保留（默认 anonymous，向后兼容）。

### 3.10 默认策略与日志

| 配置 | 行为 | 日志 |
| --- | --- | --- |
| 无 authorizer（runtime + HTTP） | allow-all | runtime 首次 `authorize()` 调用记一条 WARN（`helm-runtime` 用 `java.util.logging`） |
| 有 authorizer，HTTP 无 extractor | extractor 返回 anonymous → authorizer 决策 | 启动时 INFO：未配置 extractor |
| 有 authorizer + extractor | 正常授权 | DENY 时 INFO（含 action/resource/principal 摘要，不含 attributes） |
| HTTP 启用 + 无 authorizer | allow-all | 启动时 WARN：HTTP 已启用但未配置 authorizer，文档明示风险 |

文档（`docs/design/05-authorizer-security-context.md` §5 + `helm-http-core` README）需明示：**生产部署必须配置 `HelmAuthorizer` 与 `SecurityContextExtractor`**；allow-all 仅供开发/测试。

---

## 4. 数据流与时序

### 4.1 HTTP 请求完整时序（成功路径）

```text
Client
  |  POST /agents/support/instances/i-1/sessions/s-1/prompt
  |  Headers: X-Helm-Principal: user-42, X-Helm-Tenant: acme, X-Helm-Trace-Id: trace-9
  |  Body: {"text":"how do I reset my password?"}
  v
HelmHttpServlet.service
  |  collectHeaders(req) -> {X-Helm-Principal:[user-42], ...}
  |  ServletSecurityContextExtractor.extract(req, headers)
  |    -> HeaderSecurityContextExtractor.extract -> ctx{principal:"user-42", attributes:{tenantId:"acme"}, metadata:{traceId:"trace-9"}}
  |  new HelmHttpRequest(method, path, {}, headers, body, ctx)
  v
AuthorizedHelmHttpRouter.handle
  |  extractor.extract(request) -> ctx (已由 servlet 填入；此处为幂等再提取，或直接读 request.securityContext())
  |  delegate.handle(request) 走 method+path 匹配 -> 命中 prompt route, pathParams={agent:support, instance:i-1, session:s-1}
  |  RouteAuthorizer.authorize(matched, ctx)
  |    action = PROMPT
  |    resource = HelmResource.agent("support", {instanceId:"i-1", sessionName:"s-1"})
  |    HelmAuthorizer.authorize(ctx, PROMPT, resource) -> AuthorizationResult.ALLOWED
  |  (授权通过)
  v
promptHandler.handle(routed)
  |  ctx = routed.securityContext()
  |  runtime.prompt(AgentPromptRequest("support","i-1","s-1","how do I..."), ctx)
  v
AgentRuntime.prompt(request, ctx)
  |  executePrompt(request, operationId, ctx)
  v
AgentRuntime.executePrompt
  |  1. sessionId = "support:i-1:s-1"
  |  2. authorize(PROMPT, HelmResource.agent("support", {instanceId:"i-1", sessionName:"s-1"}), ctx)
  |       -> authorizer.authorize -> ALLOWED
  |  3. activeSessions.putIfAbsent(sessionId, TRUE)
  |  4. store.saveOperation(running)                      // admission
  |  5. appendEvent(OPERATION_STARTED, redact(payload))   // payload 不含 ctx
  |  6. agent resolve / session load / engine.run / saveSession
  |  7. store.saveOperation(succeeded)
  |  8. appendEventSafely(OPERATION_SUCCEEDED)
  |  9. finally: activeSessions.remove(sessionId)
  v
PromptResult{operationId, text}
  v
HelmHttpResponse.ok(json)
  v
Client <- 200 + {"operationId":"op_...","text":"..."}
```

### 4.2 拒绝路径

#### 4.2.1 未认证（anonymous + authorizer 配置）

```text
Client (无 X-Helm-Principal)
  v
AuthorizedHelmHttpRouter.handle
  |  extractor.extract -> HelmSecurityContext.anonymous()
  |  RouteAuthorizer.authorize(matched, ctx)
  |    检测 ctx.isAnonymous() && authorizer != null
  |    throw AuthorizationException.unauthenticated("anonymous caller")
  v
HttpErrors.toResponse(e)
  |  statusFor("UNAUTHORIZED") -> 401
  |  body: {"error":{"code":"UNAUTHORIZED","message":"anonymous caller","details":{}}}
  v
Client <- 401
```

注意：**不进入 `delegate.handle`，不调用 handler，不调用 runtime**。401 在 router 层返回，runtime 完全无感（无 operation 记录、无 event）。

#### 4.2.2 已认证但无权限（forbidden）

```text
Client (X-Helm-Principal: user-42, 但 user-42 不属于 tenant "acme" 的 support agent)
  v
AuthorizedHelmHttpRouter.handle
  |  extractor.extract -> ctx{principal:"user-42", attributes:{tenantId:"acme"}}
  |  RouteAuthorizer.authorize(matched, ctx)
  |    authorizer.authorize(ctx, PROMPT, agent("support",...))
  |    -> AuthorizationResult.denied("tenant mismatch")
  |    throw AuthorizationException.forbidden(PROMPT, resource, "tenant mismatch", "user-42 tenantId=acme but agent requires tenant=beta")
  v
HttpErrors.toResponse(e)
  |  statusFor("FORBIDDEN") -> 403
  |  body: {"error":{"code":"FORBIDDEN","message":"tenant mismatch","details":{"action":"PROMPT","resourceType":"AGENT","resourceName":"support"}}}
  |  (developerReason "user-42 tenantId=acme..." 不进 body，只进 server-side 日志)
  v
Client <- 403
```

`details` 只含 `action` / `resourceType` / `resourceName`，不暴露 principal 或 attributes 内容（对齐 §5.1）。

#### 4.2.3 直接调 AgentRuntime（CLI / 应用内嵌）

```text
Application code
  |  AgentRuntime runtime = AgentRuntime.builder().agent(...).authorizer(myAuthz).build()
  |  runtime.prompt(new AgentPromptRequest("support","i-1","s-1","..."))
  |    (无显式 ctx -> 用 defaultSecurityContext = anonymous())
  v
AgentRuntime.prompt(request)
  |  prompt(request, anonymous())
  |  executePrompt: authorize(PROMPT, resource, anonymous())
  |    myAuthz.authorize(anonymous(), PROMPT, resource)
  |    -> 若 myAuthz 拒绝 anonymous -> AuthorizationException
  v
调用方必须显式传 ctx:
  runtime.prompt(request, HelmSecurityContext.of("cli-user", Set.of("admin"), Map.of(), Map.of()))
```

CLI（`helm-cli`）在启动时构造 `HelmSecurityContext`（principal = 当前 OS 用户，roles = `Set.of("cli")`），通过 `defaultSecurityContext` 注入，使 `helm run` / `helm dev` 调用自动带身份。

### 4.3 与 admission / event 的时序约束

| 时序点 | 是否产生 operation 记录 | 是否产生 event | 是否占 session 锁 |
| --- | --- | --- | --- |
| 授权前 | 否 | 否 | 否 |
| 授权失败（401/403） | 否 | 否 | 否 |
| 授权通过 + admission `saveOperation` | 是（RUNNING） | 是（OPERATION_STARTED） | 是 |
| 执行失败 | 是（FAILED） | 是（OPERATION_FAILED） | 释放 |

约束：**授权失败不产生任何持久化副作用**。这与设计原则 4"Admission 优先"协同——admission 是"已授权的请求才进入排队/执行"。

---

## 5. 安全与边界

### 5.1 principal 不进 events / logs

| 数据 | 进 `RuntimeEventRecord.payload` | 进 HTTP error `details` | 进 server-side 日志 |
| --- | --- | --- | --- |
| `principal` | 否 | 否 | 摘要可（如 hash），原始值默认不记 |
| `roles` | 否 | 否 | 否 |
| `attributes`（如 tenantId） | 否 | 否 | 否（除非应用显式声明可记） |
| `metadata`（如 traceId） | 是 | 否 | 是 |
| `action` / `resourceType` / `resourceName` | 是 | 是 | 是 |
| `reason`（authorizer 提供） | 是 | 是 | 是 |
| `developerReason` | 否（不进 event payload） | 否（不进 HTTP body） | 是（server-side 排错） |

实现约束：

- `EventRedactor`（`helm-runtime` 既有）已对 event payload 做脱敏。本组件确保 `HelmSecurityContext` **不传给 `appendEvent` 的 payload**——payload 只含 action/resource/text 摘要（既有行为）。
- `AuthorizationException.details()` 只含 action/resource，构造时强制（§3.6 `forbidden` 工厂方法）。
- `AuthorizationException.developerDetails()` 可含 developerReason，但不进 HTTP body（`HttpErrors.toResponse` 既有逻辑只用 details）。

### 5.2 默认策略

| 场景 | 默认 | 理由 |
| --- | --- | --- |
| `AgentRuntime.Builder` 未调 `authorizer(...)` | allow-all，首次调用 WARN | dev 友好（`helm dev`、单测不需要配 authorizer） |
| `AgentRuntime.Builder` 未调 `defaultSecurityContext(...)` | `anonymous()` | 既有调用方不传 ctx 仍能工作 |
| HTTP 启用（router 非空）+ 无 authorizer | allow-all，启动 WARN | 文档明示：生产必须配 authorizer |
| HTTP 启用 + 无 `SecurityContextExtractor` | `HelmSecurityContext.anonymous()` | 所有 HTTP 调用 anonymous；若 authorizer 拒绝 anonymous 则全 401 |
| `WorkflowRuntime` 未配 authorizer | allow-all，WARN | 与 `AgentRuntime` 一致 |

allow-all 不作为生产默认——文档与启动日志都明示风险。未来若需强制（如 1.0 后），可将 allow-all 改为 fail-closed，本组件预留 `HelmAuthorizer.failClosed()` 工厂。

### 5.3 agent instance id 不代表授权

设计原则 7 的核心边界：

- `instanceId` 是 agent 实例标识（多租户、多会话隔离用），**不是权限**。
- 同一 principal 可访问多个 instanceId；同一 instanceId 可被多个 principal 访问——由应用 authorizer 决定。
- Helm 不在 runtime 内 hardcoded "instanceId owner = principal" 关系。应用若需，在 authorizer 里查 store 关联。
- `AgentContext(agentName, instanceId)`（`helm-core/.../agent/AgentContext.java:3`）**不加 principal 字段**——agent 定义阶段（`AgentDefinition.configure`）不应依赖调用方身份；身份只在 admission（prompt/dispatch/invoke）阶段注入。

### 5.4 错误响应暴露范围

对齐组件 #11 §3.3.3 与 roadmap M6 验收"error response 只含 code + safe details"：

```json
// 401
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "anonymous caller",
    "details": {}
  }
}

// 403
{
  "error": {
    "code": "FORBIDDEN",
    "message": "tenant mismatch",
    "details": {
      "action": "PROMPT",
      "resourceType": "AGENT",
      "resourceName": "support"
    }
  }
}
```

不暴露：principal、roles、attributes 内容、authorizer 内部规则 id（除非显式可暴露）、堆栈、内部路径。

### 5.5 依赖守则

| 模块 | 新增依赖 | 是否合规 |
| --- | --- | --- |
| `helm-core` | 无（`core.security` 只用 JDK） | 合规（Core-first） |
| `helm-runtime` | 无（`runtime.security` 用 `helm-core` + JDK `java.util.logging`） | 合规 |
| `helm-http-core` | 无（`http.core.security` 用 `helm-core` + 既有 Jackson） | 合规（无 Servlet/Spring） |
| `helm-http-servlet` | 无新增（既有 Jakarta Servlet） | 合规 |

校验：`helm-core` 仍不依赖 runtime/engine/HTTP/CLI/Spring/provider SDK/JDBC/logging adapter。

---

## 6. 测试策略

### 6.1 HelmAuthorizerContractTest

新增合约测试基类（`helm-core/src/test/java/io/agent/helm/core/security/HelmAuthorizerContractTest.java`，发布 test-jar）：

```java
package io.agent.helm.core.security;

import io.agent.helm.core.error.AuthorizationException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract for {@link HelmAuthorizer} implementations. Every authorizer (including AllowAllAuthorizer,
 * DenyAllAuthorizer, and application-provided ones) must pass this base class.
 */
public abstract class HelmAuthorizerContractTest {

    protected abstract HelmAuthorizer authorizer();

    @Test
    void allowedResultIsIdempotent() {
        HelmSecurityContext ctx = ctx("user-1", Set.of("viewer"));
        AuthorizationResult r1 = authorizer().authorize(ctx, HelmAction.READ_OPERATION, HelmResource.operation("op_1"));
        AuthorizationResult r2 = authorizer().authorize(ctx, HelmAction.READ_OPERATION, HelmResource.operation("op_1"));
        assertThat(r1.decision()).isEqualTo(r2.decision());
    }

    @Test
    void anonymousCallerIsHandledNotNullSafe() {
        HelmSecurityContext anon = HelmSecurityContext.anonymous();
        AuthorizationResult result = authorizer().authorize(anon, HelmAction.PROMPT, HelmResource.agent("a", Map.of()));
        // 不抛 NPE；返回决策或抛 AuthorizationException 均可
        assertThat(result).isNotNull();
    }

    @Test
    void deniedResultCarriesReasonWhenProvided() {
        HelmSecurityContext ctx = ctx("user-2", Set.of());
        AuthorizationResult result = authorizer().authorize(ctx, HelmAction.DELETE_SESSION, HelmResource.session("s-1"));
        if (!result.isAllowed()) {
            assertThat(result.reason()).isNotNull();
        }
    }

    @Test
    void throwingAuthorizationExceptionIsEquivalent() {
        // 子类可覆盖：若 authorizer 选择抛异常而非返回 denied，验证异常 details 安全
        HelmSecurityContext ctx = ctx("user-3", Set.of());
        try {
            AuthorizationResult r = authorizer().authorize(ctx, HelmAction.LIST_SESSIONS, HelmResource.session("s-2"));
            assertThat(r).isNotNull();
        } catch (AuthorizationException e) {
            assertThat(e.code()).isIn("UNAUTHORIZED", "FORBIDDEN");
            assertThat(e.details()).doesNotContainKey("principal");
            assertThat(e.details()).doesNotContainKey("roles");
        }
    }

    protected static HelmSecurityContext ctx(String principal, Set<String> roles) {
        return new HelmSecurityContext(principal, roles, Map.of(), Map.of());
    }
}
```

`AllowAllAuthorizer` / `DenyAllAuthorizer`（`helm-runtime`）必须通过该合约。

### 6.2 AgentRuntime 授权集成测试

`helm-runtime` 新增 `AgentRuntimeAuthorizationTest`：

- `prompt` 未授权 → 抛 `AuthorizationException.forbidden`，且 `store.listOperations()` 为空（无 operation 记录）。
- `prompt` 未授权 → `store.eventsForOperation` 为空（无 event）。
- `prompt` 授权 → 正常执行，operation 记录存在。
- `getOperation` 未授权 → 抛异常，不调 `store.loadOperation`（用 spy store 验证）。
- `resetSession` 未授权 → 抛异常，session 仍存在（未删除）。
- `dispatch` 未授权 → 抛异常，`store.listOperations` 不含该 operationId。
- 无 authorizer → allow-all，且日志含 WARN（用 `java.util.logging.Handler` capture 验证）。
- `defaultSecurityContext` 设置后，既有 `prompt(request)` 用 default ctx 授权。

### 6.3 HTTP 401/403 端到端

`helm-http-core` 新增 `AuthorizedRouterTest`：

| 用例 | 输入 | 期望 |
| --- | --- | --- |
| 无 principal header + authorizer 配置 | `POST /agents/a/instances/i/sessions/s/prompt` 无 `X-Helm-Principal` | 401 + `{"error":{"code":"UNAUTHORIZED",...}}` |
| principal 无权限 | `X-Helm-Principal: user-1` + authorizer 拒绝 | 403 + `{"error":{"code":"FORBIDDEN","details":{"action":"PROMPT","resourceType":"AGENT","resourceName":"a"}}}` |
| principal 有权限 | `X-Helm-Principal: user-1` + authorizer 允许 | 200（runtime 用 spy 验证收到 ctx） |
| 404 路径不触发授权 | `GET /unknown` | 404（不调 authorizer） |
| `developerDetails` 不进 body | forbidden with developerReason | body 不含 `developerReason` 字段 |
| `principal` 不进 body | forbidden | body `details` 不含 `principal` |

测试用 fake `AgentRuntime`（spy）+ fake `HelmAuthorizer`（可控决策）。

### 6.4 Servlet extractor 测试

`helm-http-servlet` 新增 `ServletSecurityContextExtractorTest`：

- `X-Helm-Principal` 存在 → 用 header 值。
- header 缺失 + `HttpServletRequest.getUserPrincipal()` 返回非 null → 用 Servlet principal。
- 两者都缺失 → `anonymous()`。
- `HttpServletRequest.isUserInRole("admin")` true → roles 含 `admin`。

用 Jetty（既有测试依赖）构造 mock `HttpServletRequest`。

### 6.5 默认策略与日志测试

- `AgentRuntime` 无 authorizer → allow-all + 首次 WARN（capture `java.util.logging.Logger` 的 `LogRecord`）。
- HTTP router 无 authorizer → 启动 WARN（验证 `AuthorizedHelmHttpRouter` 构造时记录）。
- `WorkflowRuntime` 无 authorizer → allow-all + WARN。

### 6.6 与既有测试的兼容性

- 既有 `AgentRuntime` / `WorkflowRuntime` / `HelmHttpRoutes` / `HelmHttpServlet` 测试**不传 ctx**，走 `defaultSecurityContext = anonymous()` + 无 authorizer = allow-all，应全部通过（无修改）。
- 既有 `HttpErrorContractTest`（`helm-http-core`）增加 `UNAUTHORIZED`/`FORBIDDEN` 用例（401/403 status + body 形态）。

---

## 7. 验收标准

### 7.1 M6 验收直接对齐

来源：`docs/roadmap.md:296-300`（M6 验收）。

| roadmap M6 验收项 | 本组件达成方式 |
| --- | --- |
| HTTP opt-in | 既有（`HelmHttpRouter` 已可选）；本组件确保 authorizer 也是 opt-in（默认 allow-all + WARN） |
| 每个 route 执行前可授权 | §3.8.3 `AuthorizedHelmHttpRouter` + `RouteAuthorizer`：每条 matched route 在 handler 前调用 authorizer；404 路径不触发 |
| error response 只含 code + safe details | §3.6 `AuthorizationException` details 只含 action/resourceType/resourceName + reason；`developerDetails` 与 principal 不进 body；`HttpErrors.toResponse` 既有逻辑已丢弃 message/stack |
| `helm-http-core` 无 Servlet/Spring 依赖 | §3.1 `SecurityContextExtractor` 与 `HeaderSecurityContextExtractor` 放 `helm-http-core`，只依赖 `helm-core` + 既有 Jackson；Servlet 适配在 `helm-http-servlet` |

### 7.2 本组件专属验收

#### 7.2.1 SPI 与类型落地

- [ ] `helm-core` 新增 `io.agent.helm.core.security` 包，含 `HelmSecurityContext`、`HelmAction`、`HelmResource`、`HelmAuthorizer`、`AuthorizationResult`。
- [ ] `helm-core` 新增 `AuthorizationException`（`core.error` 包），code 限定 `UNAUTHORIZED` / `FORBIDDEN`，构造器校验。
- [ ] `ErrorCode` 枚举（组件 #11）追加 `UNAUTHORIZED`、`FORBIDDEN`，并登记到 `docs/contracts/error-codes.md`。
- [ ] `HttpErrors.statusFor` 追加 `UNAUTHORIZED`→401、`FORBIDDEN`→403。
- [ ] `helm-core` 发布 test-jar 含 `HelmAuthorizerContractTest`。

#### 7.2.2 Runtime 集成

- [ ] `AgentRuntime.Builder` 新增 `authorizer(HelmAuthorizer)` 与 `defaultSecurityContext(HelmSecurityContext)`。
- [ ] `AgentRuntime` 公开方法（prompt / dispatch / getOperation / getOperationEvents / listOperations / listSessions / getSession / resetSession）新增 `*WithSecurity` 重载；既有方法委托并用 default ctx。
- [ ] `executePrompt` 在 admission（`saveOperation`）之前插入 `authorize(PROMPT, ...)`。
- [ ] `WorkflowRuntime.Builder` 同样新增 `authorizer` / `defaultSecurityContext`；`invoke` / `getRun` / `listRuns` / `getRunEvents` 新增 `*WithSecurity` 重载。
- [ ] `WorkflowRuntime.invoke` 内部构造的 `AgentRuntime` 继承外层 authorizer 与 defaultSecurityContext。
- [ ] `helm-runtime` 新增 `AllowAllAuthorizer` 与 `DenyAllAuthorizer` 参考实现，通过 `HelmAuthorizerContractTest`。

#### 7.2.3 HTTP / Servlet 集成

- [ ] `helm-http-core` 新增 `io.agent.helm.http.core.security` 包，含 `SecurityContextExtractor`、`HeaderSecurityContextExtractor`、`AuthorizedHelmHttpRouter`、`RouteAuthorizer`。
- [ ] `HelmHttpRequest` 新增 `securityContext` 字段（默认 `anonymous()`）与 `withSecurityContext` 工厂。
- [ ] `HelmHttpRoutes` 构建时同时输出 `RouteAuthorizer`（每条 route 关联 action + resource builder）。
- [ ] `helm-http-servlet` 新增 `ServletSecurityContextExtractor`，兜底 `HttpServletRequest.getUserPrincipal()`。
- [ ] `HelmHttpServlet` 新增 `HelmHttpServlet(router, extractor)` 重载。

#### 7.2.4 默认策略与日志

- [ ] 无 authorizer 时 allow-all，runtime 首次 `authorize()` 调用记 WARN（capture `java.util.logging`）。
- [ ] HTTP 启用 + 无 authorizer 启动 WARN，文档明示风险。
- [ ] 授权失败不产生 operation 记录、不产生 event、不占 session 锁（测试验证）。

#### 7.2.5 测试

- [ ] `HelmAuthorizerContractTest` 通过（AllowAll / DenyAll）。
- [ ] `AgentRuntimeAuthorizationTest` 覆盖 §6.2 全部用例。
- [ ] `AuthorizedRouterTest` 覆盖 §6.3 全部用例（含 401/403 body 形态）。
- [ ] `ServletSecurityContextExtractorTest` 覆盖 §6.4 全部用例。
- [ ] 既有 `HttpErrorContractTest` 增加 `UNAUTHORIZED` / `FORBIDDEN` 用例。
- [ ] 既有 `AgentRuntime` / `WorkflowRuntime` / `HelmHttpRoutes` / `HelmHttpServlet` 测试无修改通过（向后兼容）。

#### 7.2.6 验证命令

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

全绿，含新测试与既有测试。

---

## 8. 风险与未决项

### 8.1 风险

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| `*WithSecurity` 重载使 API 表面翻倍，调用方可能误用既有方法（忘记传 ctx） | 中 | 既有方法用 default ctx；HTTP/Servlet 强制走 override；文档明示"生产调用方应传 ctx"；静态分析可检查 `runtime.prompt(request)` 在生产代码中的出现 |
| authorizer 性能影响每个请求 | 中 | SPI 文档要求 sub-millisecond；应用 authorizer 若查 DB 应缓存；本组件不引入缓存（属应用职责） |
| allow-all 默认可能被误用于生产 | 中 | 启动 WARN + 文档明示；未来 1.0 可改 fail-closed |
| `RouteAuthorizer` 需与 `HelmHttpRoutes` route 列表保持同步 | 中 | `HelmHttpRoutes` 同时输出 router + authorizer，单一构造点；新增 route 时编译期不报错但合约测试应覆盖每条 route 的授权 |
| `WorkflowRuntime.invoke` 内部 `AgentRuntime` 继承 authorizer 可能使 workflow 内 prompt 意外被拒 | 低 | 这是预期行为（workflow 内 prompt 也应授权）；文档说明 workflow 用 workflow 的 security context |
| `HelmHttpRequest` 加字段破坏既有 record 使用方 | 低 | record 加字段不破坏二进制兼容（构造器顺序保持，新字段在末尾）；既有 `HelmHttpServlet` 已同 PR 更新 |

### 8.2 未决项

| 未决项 | 说明 | 决策时点 |
| --- | --- | --- |
| 是否提供 `AuthorizationResult.allowedWithConditions(...)` | 当前 `AuthorizationResult` 只有 ALLOW/DENY，不支持条件授权（如"允许但限速"）。条件授权属组件 #7 rate limiting。 | 组件 #7 落地时 |
| `HelmAction` 是否纳入 `EXECUTE_TOOL` | tool 层授权当前由 `AgentConfig` 收窄；是否在 tool 执行前调用 authorizer 待定。 | 组件 #2 engine hardening 落地时 |
| `metadata` 是否进 `RuntimeEventRecord` | 当前规定 metadata（traceId）可进 event payload，但未规定具体字段名。是否与组件 #8 metrics/otel 的 trace 关联字段统一待定。 | 组件 #8 落地时 |
| `RouteAuthorizer` 是否独立 SPI 还是 `HelmHttpRoutes` 内部实现 | 当前设计为 `HelmHttpRoutes` 同时输出 router + authorizer。是否需要让应用自定义 route→action 映射待定。 | M6 实施时 |
| `AuthorizationException` 是否区分 `UNAUTHENTICATED`（401）与 `FORBIDDEN`（403）的 details schema | 当前 401 的 details 为空，403 含 action/resource。是否 401 也带 action/resource 待定（泄漏资源存在性风险）。 | M6 实施时，倾向 401 不带 |
| request body size / depth / timeouts 的具体限制值 | M6 交付清单提到，但本组件只规定执行顺序（授权后才解析 body）。具体限制值属 HTTP hardening。 | M6 HTTP hardening slice |
| 是否提供 `HelmSecurityContext.current()` ThreadLocal | 当前设计 per-request 显式传参。ThreadLocal 在异步/durable 场景（组件 #9）会失效。 | 组件 #9 落地时，倾向不引入 ThreadLocal |
| `principal` 字符串是否需要格式约束（如 URN、UUID） | 当前为任意字符串。是否强制格式待定。 | M6 实施时，倾向不强制（由 extractor 决定） |

---

## 9. 与其他组件的关系

### 9.1 依赖关系

| 组件 | 关系 | 说明 |
| --- | --- | --- |
| #11 API governance | **前置依赖** | 本组件的 `AuthorizationException` code（`UNAUTHORIZED`/`FORBIDDEN`）需登记到 `ErrorCode` 注册表；`details` 白名单遵循组件 #11 §3.3.3；`core.security` 包归属遵循 SPI 包规则。 |
| #6 HTTP Client SDK | **被依赖** | client SDK 传 `X-Helm-Principal` / `X-Helm-Tenant` header（§3.8.2 约定）；client 侧不缓存授权决策（每次请求带 header）。 |
| #7 Rate limiting / admission | **协同** | rate limiter 在 authorizer 之后执行（先授权再限流，避免未授权请求消耗配额）。`RATE_LIMITED` / `OPERATION_REJECTED` code 与本组件的 `FORBIDDEN` 在 `ErrorCode` 共存。 |
| #2 Engine hardening | **协同** | engine 内 tool 执行授权待 #2 决定（见 §8.2 未决项）。本组件的 `HelmAction` 不含 `EXECUTE_TOOL`，#2 若需要可扩展。 |
| #8 Metrics / OpenTelemetry | **协同** | `metadata.traceId` 与 #8 的 trace 关联字段对齐；授权失败 metrics（`authorization.denied` counter）由 #8 提供。 |
| #9 Durable scale | **展望** | durable queue 的 admission 需在入队前授权（与本组件 admission 授权协同）；异步执行时 security context 需随队列消息传递（不依赖 ThreadLocal）。 |
| #1 Streaming API | **无关** | streaming 是 prompt 的响应形态，授权在 prompt admission 已完成，streaming 不需要额外授权点。 |
| #3 JsonSchema 扩展 | **无关** | schema 与授权正交。 |
| #4 Memory 语义检索 | **无关** | memory scope 是 `agentName:instanceId`，授权在 prompt admission 决定"能否访问该 instance"，memory 检索不额外授权。 |
| #10 Release engineering | **协同** | `core.security` 包加入 japicmp baseline（组件 #11 §3.6）；BOM 列入既有模块，本组件无新模块。 |

### 9.2 命名对齐

遵循组件 #11 §9.1 命名约束：

- SPI 接口放 `helm-core` 的 `core.security` 子包。
- `AuthorizationException` 放 `helm-core/src/main/java/io/agent/helm/core/error/`，code 在 `ErrorCode` 枚举登记。
- `HelmAuthorizerContractTest` 放 `helm-core/src/test/java/.../security/`，发布 test-jar。
- `AllowAllAuthorizer` / `DenyAllAuthorizer` 放 `helm-runtime` 的 `runtime.security` 子包（实现，非 SPI）。
- `SecurityContextExtractor` 放 `helm-http-core` 的 `http.core.security` 子包（HTTP 层 SPI）。
- `ServletSecurityContextExtractor` 放 `helm-http-servlet` 的 `servlet.security` 子包。

### 9.3 时序对齐

按 `docs/design/README.md` 第 2 节依赖图：

```text
11 API governance ──> 5 authorizer ──> 6 client sdk
                       ^
                       |
                       7 rate limiting
```

本组件应在 #11 API governance 之后落地（依赖 `ErrorCode` 注册表与包规则），在 #6 client SDK 之前（client 依赖 header 约定）。#7 rate limiting 可与本组件并行，但实施时 rate limiter 需在本组件 authorizer 之后执行（先授权再限流）。

### 9.4 与设计原则的对齐

| 设计原则（roadmap §2） | 本组件对齐方式 |
| --- | --- |
| Core-first | `HelmSecurityContext` / `HelmAuthorizer` 放 `helm-core`，无禁止依赖 |
| Contract-first | `HelmAuthorizerContractTest` 先行，`AllowAll`/`DenyAll` 参考实现 |
| Agent 与 Workflow 分离 | `HelmAction` 同时覆盖 agent 与 workflow 方法，但 resource 类型区分（AGENT vs WORKFLOW） |
| Admission 优先 | 授权在 admission（`saveOperation`）之前，未授权不产生 admission 记录 |
| 事件优先 | 授权失败不产生 event；授权通过后 event 不含 security context（metadata 例外） |
| 安全默认 | allow-all 默认 + WARN；HTTP 启用建议配 authorizer；principal 不进 events/logs |
| 应用拥有业务权限 | `HelmAuthorizer` 是 SPI，Helm 不实现 RBAC 引擎；agent instance id 不进授权决策 |
| 窄工具边界 | tool 层授权不在本组件（属 #2），但 admission 授权已收窄"能否对这个 agent 操作" |
| 可验证发布 | `HelmAuthorizerContractTest` + 集成测试 + 端到端 401/403 测试 |
