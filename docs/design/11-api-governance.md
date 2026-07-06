# 11. API Governance

Helm 在系统设计 Milestone 1–5 之后已具备完整 MVP 基座（core/engine/runtime/provider/sandbox/HTTP/CLI/Spring Boot starter/JDBC/logging observer/memory/session）。但 `docs/roadmap.md` M0 仍有四项未勾选，第 7 节阻塞项也未关闭。本组件不是新增功能能力，而是为后续所有 SPI/adapter 工作冻结 API 边界与兼容性规则——它是后续 10 个组件设计的前置条件。

---

## 实现状态（2026-07-05）

**✓ 已实现**。`ErrorCode` 注册表 + `stable()` 合约测试、`@Preview`/`@Experimental` 注解、`RuntimeStore` 拆分为 `SessionStore`/`OperationStore`/`WorkflowRunStore`/`EventStore` 子接口、`io.agent.helm` groupId、`helm-bom`、`docs/contracts/error-codes.md`。`japicmp` profile 已配置（`api-compat`）。

## 1. 背景与目标

### 1.1 为什么需要 API governance

Helm 已进入"多 adapter 并行开发"阶段：OpenAI/Anthropic provider、JDBC persistence、local sandbox、Servlet、Spring Boot starter、logging observer 都是独立 adapter。这些 adapter 都依赖 `helm-core` 的稳定 SPI。如果现在不冻结 public/internal 边界、不规定兼容性策略，会出现三类失控：

1. adapter 倒逼 core 暴露不该 public 的内部类型。
2. core 内部重构直接破坏外部 adapter，且没有 CI 信号。
3. 异常 code、details 字段语义不一致，HTTP 错误响应无法稳定映射。

### 1.2 本组件目标

| 目标 | 内容 |
| --- | --- |
| 冻结 package 三档分类 | 明确 public / SPI / internal 包规则，列出 helm-core 现有包归类。 |
| 定义 pre-1.0 兼容策略 | 0.x 版本下破坏性变更边界、`@Preview`/`@Experimental` 注解、deprecation 周期。 |
| 建立 exception code 注册表 | 集中登记所有 `HelmException` code，约束 details / developerDetails 字段语义。 |
| 决策 Maven groupId/artifact | 推荐 `io.agent.helm` 作为 groupId，给出 BOM 策略。 |
| 决策 RuntimeStore 拆分 | 保持聚合 facade + 子接口，给出迁移路径。 |
| 引入 API 兼容性测试 | japicmp baseline 对比，public/spi 破坏变更触发 CI 失败。 |
| JPMS 展望 | 阶段性建议：先包约定，后 module-info。 |

### 1.3 不解决什么

- 不重新设计 `HelmException` 类型层级（只在现有三段式上加约束）。
- 不引入新 SPI（仅规定既有 SPI 的兼容规则）。
- 不实现 JPMS 模块化（仅给出阶段性建议）。
- 不规定 license（属第 10 组件 Release Engineering）。

---

## 2. 现状与缺口

> **注**：以下缺口分析反映设计时的现状；当前实现状态见文首「实现状态（2026-07-05）」。

### 2.1 roadmap M0 未完成项

来源：`docs/roadmap.md` 第 5 节 M0 交付清单。

| 未勾选项 | 现状 | 本文档解决位置 |
| --- | --- | --- |
| 确认 Maven groupId/artifact 命名策略 | `pom.xml` 当前 `groupId=io.agent`、`artifactId=helm`，与生产包命名空间 `io.agent.helm` 不一致 | §3.4 |
| 写明 public package、SPI package、internal package 规则 | 无显式规则，core 子包混在一起，runtime/engine 包没有 `.internal` 后缀 | §3.1 |
| 明确 pre-1.0 API compatibility policy | 无文档 | §3.2 |
| public exception 都有稳定 code 和 safe details | `HelmException` 三段式已存在，但子类 code 命名不统一，无集中注册表 | §3.3 |

### 2.2 roadmap 第 7 节阻塞项

来源：`docs/roadmap.md` 第 7 节"当前阻塞项"。

| Blocker | Owner | 本文档解决 |
| --- | --- | --- |
| Maven groupId / 发布命名空间未最终确定 | project | §3.4 |
| `RuntimeStore` 是否拆分子接口未定 | project | §3.5 |

### 2.3 代码现状

`HelmException`（`helm-core/src/main/java/io/agent/helm/core/error/HelmException.java`）已有三段式：

```java
public abstract class HelmException extends RuntimeException {
    private final String code;                    // 稳定字符串
    private final Map<String, Object> details;   // 可安全暴露
    private final Map<String, Object> developerDetails;  // 不进 events/logs/safe errors
}
```

现有子类 code 审计（见 §3.3 表），存在两类问题：

1. 命名风格不统一：`PERSISTENCE_ERROR` / `SANDBOX_ERROR` 用 `_ERROR` 后缀，但 `SESSION_BUSY` / `CONTEXT_OVERFLOW` / `AGENT_NOT_FOUND` 不用；`TOOL_EXECUTION_FAILED` / `VALIDATION_FAILED` 用 `_FAILED` 后缀。
2. `ProviderException` 已支持多 code（`PROVIDER_ERROR` / `PROVIDER_RATE_LIMITED` / `PROVIDER_TIMEOUT`），其他子类是单 code，没有约束子类是否允许多 code。

包结构现状（`find helm-core/src/main/java -type d`）：

```text
io.agent.helm.core
  ├── agent      (AgentConfig, AgentDefinition, AgentContext)
  ├── error      (HelmException + 11 子类)
  ├── event     (RuntimeEventRecord, RuntimeEventObserver)
  ├── memory     (MemoryStore, MemoryRecord)
  ├── message   (HelmMessage, ContentBlock)
  ├── model     (ModelProvider, ModelRef, ModelRequest, ModelStreamEvent)
  ├── sandbox    (Sandbox, SandboxFileSystem, SandboxShell)
  ├── skill     (SkillDefinition)
  ├── store     (RuntimeStore, AgentSessionState, OperationRecord, WorkflowRunRecord, OperationStatus, WorkflowRunStatus)
  ├── tool      (Tool, ToolContext, ToolResult, ToolCall)
  ├── type      (JsonSchema, TypeDescriptor)
  └── workflow  (WorkflowDefinition, WorkflowConfig)
```

runtime/engine 包现状（无 `.internal` 后缀）：

```text
io.agent.helm.runtime        (AgentRuntime, WorkflowRuntime, InMemoryRuntimeStore, FakeProvider, ...)
io.agent.helm.engine         (AgentEngine, TurnRunner, ToolCallOrchestrator, ...)
io.agent.helm.persistence.jdbc (JdbcRuntimeStore)
io.agent.helm.observability.logging (LoggingRuntimeObserver)
```

`docs/contracts/runtime-store.md` 第 65 行明确："Splitting it into `SessionStore`, `OperationStore`, `WorkflowRunStore`, and `EventStore` remains a documented M2 design decision." 本文档 §3.5 给出该决策。

---

## 3. 设计方案

### 3.1 Package 三档分类规则

#### 3.1.1 三档定义

| 档位 | 含义 | 兼容性承诺 | 命名约定 |
| --- | --- | --- | --- |
| **Public API** | 应用代码直接 import 使用的类型（builder、record、配置类、runtime 入口）。 | pre-1.0：minor 版本可破坏，但必须 deprecate 一个 minor。1.0 后：major 版本才破坏。 | 顶层 + 业务子包，无特殊后缀。如 `io.agent.helm.core.agent`、`io.agent.helm.runtime`。 |
| **SPI** | 可被 adapter 实现的接口 + 其依赖的合约类型（record、enum、异常）。 | 与 Public API 同等承诺。SPI 变更必须先更新合约测试。 | 接口所在的 core 子包。如 `io.agent.helm.core.model`、`io.agent.helm.core.store`。 |
| **Internal** | 框架自身实现细节，不保证稳定，应用与 adapter 都不应直接依赖。 | 无兼容承诺，可任意重构。 | 加 `.internal` 后缀。如 `io.agent.helm.runtime.internal`、`io.agent.helm.engine.internal`。 |

#### 3.1.2 helm-core 现有包归类

`helm-core` 的所有子包都是 SPI 或 Public API（core 本身不含实现细节）：

| 包 | 档位 | 说明 |
| --- | --- | --- |
| `io.agent.helm.core.agent` | Public + SPI | `AgentDefinition` 是 SPI（应用实现），`AgentConfig` 是 Public（应用使用）。 |
| `io.agent.helm.core.error` | Public | `HelmException` 与所有子类是公开错误契约。 |
| `io.agent.helm.core.event` | SPI + Public | `RuntimeEventObserver` 是 SPI，`RuntimeEventRecord` 是 Public。 |
| `io.agent.helm.core.memory` | SPI + Public | `MemoryStore` 是 SPI，`MemoryRecord` 是 Public。 |
| `io.agent.helm.core.message` | Public | 消息模型，应用直接使用。 |
| `io.agent.helm.core.model` | SPI + Public | `ModelProvider` 是 SPI，`ModelRef`/`ModelRequest`/`ModelStreamEvent` 是 Public。 |
| `io.agent.helm.core.sandbox` | SPI + Public | `Sandbox` 是 SPI，`SandboxFileSystem`/`SandboxShell` 是 Public。 |
| `io.agent.helm.core.skill` | Public | `SkillDefinition` 是值类型。 |
| `io.agent.helm.core.store` | SPI + Public | `RuntimeStore`/`MemoryStore` 落点见 §3.5。 |
| `io.agent.helm.core.tool` | SPI + Public | `Tool` 是 SPI（应用实现），`ToolContext`/`ToolResult` 是 Public。 |
| `io.agent.helm.core.type` | Public | `JsonSchema`、`TypeDescriptor`。 |
| `io.agent.helm.core.workflow` | SPI + Public | `WorkflowDefinition` 是 SPI。 |

#### 3.1.3 runtime / engine / adapter 包重构

当前 `io.agent.helm.runtime`、`io.agent.helm.engine` 直接放实现类（`InMemoryRuntimeStore`、`FakeProvider`、`AgentEngine`），应用不应直接 import。规则：

1. **应用面向 Public API 编程**：`AgentRuntime`、`WorkflowRuntime`、`AgentConfig.builder()` 等保留在 `io.agent.helm.runtime` 顶层。
2. **实现细节移入 `.internal` 子包**：`InMemoryRuntimeStore` → `io.agent.helm.runtime.internal.store`；`FakeProvider` → `io.agent.helm.runtime.internal.provider`；`EventRedactor` → `io.agent.helm.runtime.internal.event`；`HarnessFactory`、`OperationRunner` → `io.agent.helm.runtime.internal`。
3. **engine 同理**：`TurnRunner`、`ToolCallOrchestrator`、`ModelStreamNormalizer`、`ContextManager` → `io.agent.helm.engine.internal`。`AgentEngine` 接口（若需暴露给 runtime）保留顶层。
4. **adapter 模块**：`helm-persistence-jdbc` 包 `io.agent.helm.persistence.jdbc`、`helm-observability-logging` 包 `io.agent.helm.observability.logging`——这些是 adapter 实现，本身就面向 SPI，不需要 `.internal`（整个模块对外只暴露实现类入口）。

#### 3.1.4 可见性约束（编译期）

阶段 1（本组件落地时）：用 package-private 与 Javadoc `@since` / `@internal` 标签约束，不引入 JPMS。

- core 子包内的实现类（如 `core.store` 里的 helper）一律 package-private。
- runtime/engine 实现类移入 `.internal` 子包后，构造器设为 package-private，由顶层 `AgentRuntime` 等组装。
- Javadoc 对 internal 类型加 `{@code @apiNote internal}` 或 `@hidden`（JDK 8+ 标准），让 Javadoc 不暴露。

阶段 2（§3.7 JPMS）：用 `module-info.java` 的 `exports` 强制可见性。

### 3.2 Pre-1.0 兼容策略

#### 3.2.1 版本语义

Helm 当前 `0.1.0-SNAPSHOT`。Pre-1.0 采用 **0.x.y** 语义：

| 版本段 | 破坏性变更是否允许 |
| --- | --- |
| `0.minor.patch` 跨 minor | **允许**破坏 Public/SPI，但必须满足 §3.2.2 流程。 |
| `0.minor.patch` 跨 patch | 不允许破坏 Public/SPI；只能修 bug、改 internal、补 test。 |
| `1.major.minor` 起进入正式兼容承诺 | major 才允许破坏。 |

#### 3.2.2 破坏性变更流程（pre-1.0）

任何对 Public/SPI 的破坏性变更（删除类型、改签名、改 enum 值、改 record 字段、改异常 code）必须：

1. **提案**：在 PR 描述里写明"Breaking Change"，列出影响的 public/spi 类型与 adapter。
2. **deprecate 先行**：被移除的 API 必须先在 *上一个 minor* 标 `@Deprecated(forRemoval = true, since = "0.x")`。新增替代 API 后再移除。
3. **baseline 对比**：PR 必须更新 `docs/contracts/api-baseline.md`，japicmp 报告贴在 PR comment（见 §6.1）。
4. **adapter 同步**：所有 in-tree adapter（OpenAI/Anthropic/JDBC/Servlet/Spring Boot/CLI/logging）必须同 PR 更新通过。

例外：`@Preview` / `@Experimental` 标注的 API 可直接破坏，不需 deprecate（见 §3.2.3）。

#### 3.2.3 不稳定 API 注解

新增两个注解（位于 `helm-core` 的 `io.agent.helm.core.annotation` 子包）：

```java
package io.agent.helm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public API element as preview. Preview APIs may be changed or removed in any 0.x release without prior
 * deprecation. Adapters and applications should not depend on preview APIs in production.
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Preview {
    String value() default "";
}
```

```java
package io.agent.helm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an SPI or public API as experimental: the contract is being validated and may change between minor versions
 * based on adapter feedback. Unlike {@link Preview}, experimental APIs are expected to stabilize; they just haven't
 * yet. Implementors should track the corresponding contract test for the final shape.
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Experimental {
    String value() default "";
}
```

使用规则：

| 注解 | 用途 | 兼容承诺 | 当前建议标注 |
| --- | --- | --- | --- |
| `@Preview` | 探索性 API，可能整体移除。 | 无。任意 minor 可破坏。 | 新增的 streaming 暴露 API（组件 #1）、durable queue API（组件 #9）。 |
| `@Experimental` | SPI 形态基本确定，但细节可能在 adapter 反馈后调整。 | minor 版本可调签名，但不无故删除。 | `MemoryStore`（M5 新增，语义检索替换点待定）、`RuntimeEventObserver`（事件 capture policy 未冻结）。 |
| `@Deprecated(forRemoval = true, since = "0.x")` | 即将移除。 | 至少存在一个 minor 才能移除。 | 当前无（基座尚未发布）。 |

#### 3.2.4 SPI 变更额外约束

SPI 接口（`ModelProvider`、`Sandbox`、`RuntimeStore`、`MemoryStore`、`RuntimeEventObserver`、`Tool`、`AgentDefinition`、`WorkflowDefinition`、`HelmAuthorizer`）的任何变更必须：

1. 先更新对应的 `ContractTest` 抽象基类（位于 `helm-core/src/test/java/.../ContractTest.java`，发布 test-jar）。
2. in-tree adapter（InMemory / JDBC / OpenAI / Anthropic / LocalSandbox / LoggingRuntimeObserver）必须先通过新合约测试。
3. SPI 加方法时优先提供 default method，减少强制实现负担。

### 3.3 Exception code 注册表

#### 3.3.1 注册表位置与形态

新建 `docs/contracts/error-codes.md` 作为人类可读注册表，并由一个 core 内枚举 `ErrorCode` 作为代码内单一来源：

```java
package io.agent.helm.core.error;

/**
 * Stable error code registry for {@link HelmException}. Codes are stable identifiers exposed to HTTP clients and
 * persisted in events. Add new codes only by appending to this enum and updating {@code docs/contracts/error-codes.md}.
 *
 * @since 0.2.0
 */
public enum ErrorCode {
    // Agent / workflow lookup
    AGENT_NOT_FOUND,
    WORKFLOW_NOT_FOUND,
    PROVIDER_NOT_FOUND,

    // Provider failures
    PROVIDER_ERROR,
    PROVIDER_RATE_LIMITED,
    PROVIDER_TIMEOUT,

    // Engine / context
    CONTEXT_OVERFLOW,
    SESSION_BUSY,

    // Tool / sandbox / validation
    TOOL_EXECUTION_FAILED,
    SANDBOX_ERROR,
    VALIDATION_FAILED,

    // Persistence
    PERSISTENCE_ERROR;

    public String stable() {
        return name();
    }
}
```

`HelmException` 的 `code()` 字段值必须等于 `ErrorCode.<NAME>.stable()`。子类构造器校验：

```java
public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;

    protected HelmException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = code.stable();
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    /** Legacy constructor for adapters that pass raw code strings. Validated against {@link ErrorCode}. */
    protected HelmException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = validate(code);
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    private static String validate(String code) {
        try {
            return ErrorCode.valueOf(code).stable();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown HelmException code: " + code, e);
        }
    }
}
```

#### 3.3.2 现有 code 审计与统一

| 子类 | 当前 code | 状态 | 动作 |
| --- | --- | --- | --- |
| `AgentNotFoundException` | `AGENT_NOT_FOUND` | 合规 | 保留 |
| `WorkflowNotFoundException` | `WORKFLOW_NOT_FOUND` | 合规 | 保留 |
| `ProviderNotFoundException` | `PROVIDER_NOT_FOUND` | 合规 | 保留 |
| `ProviderException` | `PROVIDER_ERROR` / `PROVIDER_RATE_LIMITED` / `PROVIDER_TIMEOUT` | 合规（多 code 已支持） | 保留 |
| `ContextOverflowException` | `CONTEXT_OVERFLOW` | 合规 | 保留 |
| `SessionBusyException` | `SESSION_BUSY` | 合规 | 保留 |
| `ToolExecutionException` | `TOOL_EXECUTION_FAILED` | 合规 | 保留 |
| `SandboxException` | `SANDBOX_ERROR` | 合规 | 保留 |
| `ValidationException` | `VALIDATION_FAILED` | 合规 | 保留 |
| `PersistenceException` | `PERSISTENCE_ERROR` | 合规 | 保留 |

结论：现有 11 个 code 命名虽风格不完全统一（`_ERROR` / `_FAILED` / 无后缀），但已稳定使用且语义清晰，**不做重命名**。规则是：现有 code 冻结，新增 code 在 `ErrorCode` 注册表中追加，命名遵循 `SCREAMING_SNAKE_CASE`，避免与既有 code 冲突。

`ProviderException` 的多 code 模式允许其他子类借鉴：当一个异常类需要区分多种稳定 failure kind 时，构造器接受 `ErrorCode` 参数；否则固定 code。

#### 3.3.3 details 字段白名单

`details` 是可安全暴露给 HTTP 调用者、可持久化到 events 的字段。约束：

| 允许放入 details | 禁止放入 details |
| --- | --- |
| 资源标识符：agentName、instanceId、sessionId、operationId、runId、toolName、workflowName | 凭据、API key、token |
| 错误元数据：errorCode、retryAfter（毫秒）、providerName、modelRef | 完整 prompt、model output、tool input/output |
| 边界参数：limit、requestedSize、actualSize | 本地文件路径、堆栈、配置内部值 |
| 校验字段：fieldName、constraint | 用户 PII（除非显式标注可暴露） |

`developerDetails` 是给开发者排错用的，约束：

- **不进 events**：`RuntimeEventRecord.payload` 中不得包含 `developerDetails`。
- **不进 safe HTTP 错误响应**：HTTP adapter 返回给调用者的 error body 只含 `code` + `details`。
- **可进 server-side 日志**：logging observer 可记录 `developerDetails`，但默认 redact（参见组件 #8 metrics/otel）。

#### 3.3.4 HTTP 错误响应形态

```java
// helm-http-core 错误响应 DTO（仅 code + details，不含 developerDetails / message / stack）
public record HelmErrorResponse(String code, Map<String, Object> details) {}
```

`helm-http-core` 的 `HttpErrorMapper` 把 `HelmException` 映射为 `HelmErrorResponse`，丢弃 `developerDetails` 与 `getMessage()`、`getStackTrace()`。具体 HTTP status 映射由组件 #5（authorizer）和 #6（client SDK）细化。

### 3.4 Maven groupId / artifact 决策

#### 3.4.1 决策

| 项 | 决策 | 理由 |
| --- | --- | --- |
| groupId | `io.agent.helm` | 与生产 Java 包命名空间 `io.agent.helm` 完全一致（`AGENTS.md` 已声明），避免发布坐标与 import 包名歧义。当前 `pom.xml` 用 `io.agent`，需迁移。 |
| artifactId | 模块名（`helm-core`、`helm-runtime`、`helm-provider-openai` 等） | 已是现状，保留。 |
| version | `0.x.0-SNAPSHOT` → `0.x.0` | pre-1.0 语义见 §3.2.1。当前 `0.1.0-SNAPSHOT`。 |
| BOM | 新增 `helm-bom` 模块 | 见 §3.4.2。 |

#### 3.4.2 BOM 模块

新增 `helm-bom` 模块（packaging=pom），统一管理所有 helm 模块版本，应用引入即可：

```xml
<!-- helm-bom/pom.xml -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm</artifactId>
    <version>0.2.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-bom</artifactId>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-runtime</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-agent-engine</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-persistence-jdbc</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-provider-openai</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-provider-anthropic</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-sandbox-local</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-http-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-http-servlet</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-cli</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-spring-boot-starter</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-observability-logging</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

应用消费：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-bom</artifactId>
      <version>0.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

#### 3.4.3 groupId 迁移

当前 `pom.xml` `<groupId>io.agent</groupId>` 需迁移到 `io.agent.helm`。迁移步骤：

1. 根 `pom.xml` 改 groupId。
2. 所有子模块 pom `<parent>` 改 groupId。
3. 所有 `<dependency>` 中跨模块引用改 groupId。
4. `mvn verify` 通过。
5. 因为尚未发布，无发布坐标兼容问题。

### 3.5 RuntimeStore 拆分决策

#### 3.5.1 决策

**保持 `RuntimeStore` 作为聚合 facade，同时提供细粒度子接口，adapter 可选择性实现。** `MemoryStore` 保持独立 SPI（scope 语义不同，按 `agentName:instanceId` 而非 sessionId）。

理由：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 单 facade（现状） | adapter 实现简单，runtime 调用方只需一个依赖。 | adapter 被迫实现全部方法（即使只关心 session）；无法表达"只读 events"等能力。 |
| 直接拆 4 个独立接口 | adapter 可选择性实现。 | runtime 调用方需要持有 4 个引用，组装复杂；现有 `JdbcRuntimeStore` 必须改为 4 个类，破坏既有代码。 |
| **聚合 facade + 子接口**（推荐） | 既有 facade 不变，新 adapter 可只实现子接口；runtime 内部按子接口注入；合约测试可针对单一子接口。 | 需要 facade 接口 extends 多个子接口，类型关系略复杂。 |

#### 3.5.2 子接口定义

```java
package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.List;
import java.util.Optional;

/** Session lifecycle persistence. */
public interface SessionStore {
    Optional<AgentSessionState> loadSession(String sessionId);
    void saveSession(AgentSessionState session);
    List<AgentSessionState> listSessions();
    void deleteSession(String sessionId);
}

/** Agent operation records. */
public interface OperationStore {
    void saveOperation(OperationRecord operation);
    Optional<OperationRecord> loadOperation(String operationId);
    List<OperationRecord> listOperations();
}

/** Workflow run records. */
public interface WorkflowRunStore {
    void saveWorkflowRun(WorkflowRunRecord run);
    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);
    List<WorkflowRunRecord> listWorkflowRuns();
}

/** Runtime event append-only log. */
public interface EventStore {
    void appendEvent(RuntimeEventRecord event);
    List<RuntimeEventRecord> eventsForOperation(String operationId);
    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId);
}
```

`RuntimeStore` 改为聚合：

```java
package io.agent.helm.core.store;

public interface RuntimeStore extends SessionStore, OperationStore, WorkflowRunStore, EventStore {}
```

#### 3.5.3 迁移路径（不破坏 `JdbcRuntimeStore`）

1. **新增 4 个子接口**（上文），`RuntimeStore extends` 四者。
2. **既有实现不变**：`InMemoryRuntimeStore implements RuntimeStore`、`JdbcRuntimeStore implements RuntimeStore` 仍通过——它们已经实现了所有方法。
3. **`RuntimeStoreContractTest` 拆分**：保留为聚合测试基类（验证完整 facade），新增 `SessionStoreContractTest` / `OperationStoreContractTest` / `WorkflowRunStoreContractTest` / `EventStoreContractTest`，针对子接口行为。既有 in-memory / JDBC 实现因实现 `RuntimeStore` 自动通过所有子接口测试。
4. **runtime 内部按子接口注入**：`AgentRuntime` 只依赖 `SessionStore` + `OperationStore` + `EventStore`；`WorkflowRuntime` 只依赖 `WorkflowRunStore` + `EventStore`。这样未来可支持"只实现 SessionStore 的内存型 + JDBC-backed EventStore"混合。
5. **新 adapter 可只实现子接口**：例如纯事件 sink adapter `helm-observability-eventstore` 只 `implements EventStore`，不需要 stub 出 session/operation 方法。

不破坏现有 `JdbcRuntimeStore` 的关键：`RuntimeStore` 仍是接口，且仍 `implements` 全部子接口，老代码 `RuntimeStore store = new JdbcRuntimeStore(ds)` 不变。

#### 3.5.4 MemoryStore 不合并

`MemoryStore`（`helm-core/src/main/java/io/agent/helm/core/memory/MemoryStore.java`）独立保留，不并入 `RuntimeStore` 子接口。理由：

| 维度 | RuntimeStore | MemoryStore |
| --- | --- | --- |
| scope | sessionId / operationId / runId | `agentName:instanceId`（跨 session） |
| 生命周期 | session 删除时数据级联 | agent instance 存在即保留 |
| 写入路径 | runtime 内部 | tool `save_memory` + 应用 |
| 替换动机 | JDBC/事件 sink | 向量检索（组件 #4） |

合并会让 scope 概念混乱，且 `MemoryStore` 的替换点是 `search`（语义检索），与 `RuntimeStore` 的并发/版本合约无关。

### 3.6 API 兼容性测试

#### 3.6.1 工具选择

采用 **japicmp**（Maven 插件 `com.github.siom79.japicmp:japicmp-maven-plugin`），原因：

- 直接对比 baseline jar 与当前构建 jar 的 public/spi 签名。
- 支持 `<parameter>` 精细控制：只检查 public 方法、忽略 internal 包、忽略 `@Preview` 标注。
- 与 Maven 多模块兼容，每个模块独立配置。

替代方案 Revapi 也可，但 japicmp 配置更直观且社区活跃。

#### 3.6.2 配置

每个需要兼容承诺的模块（`helm-core`、`helm-agent-engine`、`helm-runtime`）`pom.xml` 加：

```xml
<plugin>
  <groupId>com.github.siom79.japicmp</groupId>
  <artifactId>japicmp-maven-plugin</artifactId>
  <version>0.23.0</version>
  <configuration>
    <oldVersion>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-core</artifactId>
        <version>${api.baseline.version}</version>
        <type>jar</type>
      </dependency>
    </oldVersion>
    <newVersion>
      <file>
        <path>${project.build.directory}/${project.build.finalName}.jar</path>
      </file>
    </newVersion>
    <parameter>
      <onlyModified>false</onlyModified>
      <onlyBinaryIncompatible>false</onlyBinaryIncompatible>
      <breakBuildOnModifications>true</breakBuildOnModifications>
      <breakBuildOnBinaryIncompatibleModifications>true</breakBuildOnBinaryIncompatibleModifications>
      <includes>
        <include>io.agent.helm.core</include>
        <include>io.agent.helm.core.agent</include>
        <include>io.agent.helm.core.error</include>
        <include>io.agent.helm.core.event</include>
        <include>io.agent.helm.core.memory</include>
        <include>io.agent.helm.core.message</include>
        <include>io.agent.helm.core.model</include>
        <include>io.agent.helm.core.sandbox</include>
        <include>io.agent.helm.core.skill</include>
        <include>io.agent.helm.core.store</include>
        <include>io.agent.helm.core.tool</include>
        <include>io.agent.helm.core.type</include>
        <include>io.agent.helm.core.workflow</include>
        <include>io.agent.helm.core.annotation</include>
      </includes>
      <excludes>
        <exclude>io.agent.helm.runtime.internal</exclude>
        <exclude>io.agent.helm.engine.internal</exclude>
      </excludes>
      <ignoreMissingClasses>true</ignoreMissingClasses>
    </parameter>
  </configuration>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>cmp</goal></goals>
    </execution>
  </executions>
</plugin>
```

`api.baseline.version` 在根 pom `<properties>` 定义，每个发布版 bump：

```xml
<properties>
  <api.baseline.version>0.1.0</api.baseline.version>
</properties>
```

#### 3.6.3 baseline jar 来源

第一次接入时无历史 jar。流程：

1. **首次接入**：`api.baseline.version` 留空，japicmp 跳过（用 maven profile 控制）。发布 `0.2.0` 后把 `0.2.0` 设为 baseline。
2. **后续每次 PR**：CI 拉取 baseline 版本 jar（从 Maven 仓库或 `target/` 缓存），与新构建 jar 对比。
3. **正式破坏变更**：发布新版前 bump `api.baseline.version`，更新 `docs/contracts/api-baseline.md` 写明破坏清单。

#### 3.6.4 `@Preview` / `@Experimental` 豁免

japicmp 默认不识别自定义注解。两种处理方式：

1. 简单方式：把 `@Preview` 标注的 API 放入独立包 `io.agent.helm.core.preview`，japicmp `<excludes>` 排除该包。
2. 严格方式：写 japicmp `japicmp-filter` 自定义过滤规则识别 `@Preview`。M0 阶段先用方式 1，组件 #1 streaming 落地时再决定。

### 3.7 JPMS（模块化）展望

#### 3.7.1 阶段性建议

| 阶段 | 时机 | 做法 |
| --- | --- | --- |
| 阶段 1（现在） | M0 落地 | 包约定 + `@internal` Javadoc 标签 + japicmp baseline。无 `module-info.java`。 |
| 阶段 2 | 1.0 发布前 | 每个 jar 加 `module-info.java`，`exports` 仅 public/spi 包，`requires` 显式声明依赖。`opens` internal 包给 reflection（兼容 Jackson 等）。 |
| 阶段 3 | 1.x | internal 包完全不 `exports`，强制 reflection 失败。 |

#### 3.7.2 阶段 2 module-info 形态（预览）

```java
// helm-core/src/main/java/module-info.java
module io.agent.helm.core {
  // 公开包：public API + SPI
  exports io.agent.helm.core.agent;
  exports io.agent.helm.core.annotation;
  exports io.agent.helm.core.error;
  exports io.agent.helm.core.event;
  exports io.agent.helm.core.memory;
  exports io.agent.helm.core.message;
  exports io.agent.helm.core.model;
  exports io.agent.helm.core.sandbox;
  exports io.agent.helm.core.skill;
  exports io.agent.helm.core.store;
  exports io.agent.helm.core.tool;
  exports io.agent.helm.core.type;
  exports io.agent.helm.core.workflow;

  // core 只依赖 JDK 与 java.util.concurrent.Flow（已内置），无外部 requires
}
```

```java
// helm-runtime/src/main/java/module-info.java
module io.agent.helm.runtime {
  requires io.agent.helm.core;
  requires io.agent.helm.engine;

  // runtime 顶层公开 AgentRuntime / WorkflowRuntime
  exports io.agent.helm.runtime;

  // internal 包不 exports
  // io.agent.helm.runtime.internal.* 对其他模块不可见
}
```

阶段 2 之前不引入的原因：

- JPMS 与 Maven 多模块 + 测试 classpath 有摩擦（surefire `<useModulePath>false</useModulePath>` 已设）。
- adapter 模块（OpenAI/Anthropic SDK）引入第三方依赖，部分尚未模块化，`automatic` 模块阶段过渡更稳。
- 先用包约定 + japicmp 验证团队习惯，再上 JPMS 强制。

---

## 4. 数据流与时序：API 变更评审流程

### 4.1 流程图

```text
┌────────────┐    提案       ┌──────────────┐    baseline 对比   ┌──────────────┐
│ Developer  │ ───────────> │ PR draft      │ ───────────────> │ japicmp      │
│ (本地改 API)│              │ + Breaking    │                   │ (CI step)    │
└────────────┘              │   Change 标签 │                   └──────┬───────┘
                            │ + 更新合约    │                          │
                            │   测试        │                          │ 破坏报告
                            └──────┬───────┘                          │
                                   │                                   ▼
                                   │                          ┌──────────────────┐
                                   │                          │ 是否含 @Preview?  │
                                   │                          └────┬─────────────┘
                                   │                               │ 否
                                   │                               ▼
                                   │              ┌──────────────────────────────┐
                                   │              │ 是否已 @Deprecated(forRemoval)│
                                   │              │ 至少一个 minor？              │
                                   │              └────┬───────────┬─────────────┘
                                   │                   │ 是        │ 否
                                   │                   ▼           ▼
                                   │              ┌──────────┐  ┌──────────────┐
                                   │              │ 通过     │  │ CI 失败     │
                                   │              └────┬─────┘  │ 要求先      │
                                   │                   │           │ deprecate   │
                                   │                   ▼           └─────────────┘
                                   │          ┌──────────────────┐
                                   └────────> │ Code review      │
                                              │ (检查包归类、    │
                                              │  details 白名单、 │
                                              │  ErrorCode 注册) │
                                              └────┬─────────────┘
                                                   │ 通过
                                                   ▼
                                          ┌──────────────────┐
                                          │ 合并 + bump       │
                                          │ baseline version  │
                                          │ (若破坏)          │
                                          └────┬─────────────┘
                                               │
                                               ▼
                                          ┌──────────────────┐
                                          │ 发布 0.x.0        │
                                          │ 更新 api-baseline│
                                          │ .md + CHANGELOG │
                                          └──────────────────┘
```

### 4.2 评审检查清单

PR 触及 public/spi 时，reviewer 必须确认：

1. **包归类**：新增类型是否落在正确档位（public/spi/internal）？
2. **ErrorCode 注册**：新增 `HelmException` 子类的 code 是否在 `ErrorCode` 枚举中登记？是否更新 `docs/contracts/error-codes.md`？
3. **details 白名单**：`details` 字段是否只含安全元数据？`developerDetails` 是否被误用到 events 或 HTTP 响应？
4. **合约测试**：SPI 改动是否同步更新了 `ContractTest` 抽象基类？in-tree adapter 是否通过？
5. **baseline 报告**：japicmp 报告是否贴在 PR？破坏变更是否已先 deprecate？
6. **`@Preview` / `@Experimental`**：新 API 是否需要标注不稳定注解？
7. **依赖守则**：core 是否引入了禁止依赖（Spring/Servlet/JDBC/SDK/logging）？

### 4.3 时序示例：新增 SPI 方法

以 `MemoryStore` 加 `searchByTags(String scopeId, List<String> tags)` 为例：

| 阶段 | 动作 | 产出 |
| --- | --- | --- |
| T1 | PR：`MemoryStore` 加 `default List<MemoryRecord> searchByTags(...)`，更新 `MemoryStoreContractTest` 加 `searchByTagsByScope` 测试。 | default method 不破坏 adapter。 |
| T2 | `InMemoryMemoryStore`、`JdbcMemoryStore` 实现 `searchByTags`。 | in-tree adapter 通过合约测试。 |
| T3 | japicmp 报告：新增 default method，非破坏。 | CI 绿。 |
| T4 | 合并，发布 `0.x.0`，bump baseline。 | 不需 deprecate。 |

若改为非 default 方法（强制所有 adapter 实现）：T1 必须先在一个 minor 标 `@Deprecated` 旧形态，下一个 minor 再移除——pre-1.0 也建议遵循，给 in-tree adapter 留窗口。

---

## 5. 安全与边界

### 5.1 默认值

| 项 | 默认 | 理由 |
| --- | --- | --- |
| HTTP 错误响应暴露范围 | 只 `code` + `details` | `developerDetails`、`message`、堆栈绝不外泄。 |
| 事件 payload 暴露范围 | 只 `details` | `developerDetails` 不进 `RuntimeEventRecord.payload`。 |
| 日志暴露范围 | `developerDetails` 默认 redact | logging observer 默认只记 metadata/摘要（组件 #8）。 |
| japicmp 严格度 | 破坏即 CI 失败 | 但 `@Preview` 包豁免。 |
| 包可见性 | internal 包对应用 reflection 可访问（阶段 1） | JPMS 阶段 2 才强制。 |

### 5.2 错误映射边界

`HelmException` 三段式在不同出口的暴露规则：

| 出口 | code | message | details | developerDetails |
| --- | --- | --- | --- | --- |
| HTTP 响应 body | 暴露 | 不暴露 | 暴露（白名单校验） | 不暴露 |
| `RuntimeEventRecord.payload` | 暴露 | 不暴露 | 暴露 | 不暴露 |
| server-side 日志 | 暴露 | 暴露 | 暴露 | 默认 redact，可配置 |
| 持久化 `OperationRecord.error` | 暴露 | 不暴露 | 暴露 | 不暴露 |

### 5.3 依赖守则

本组件不引入任何 core 禁止依赖：

- `helm-core` 仅新增 `io.agent.helm.core.annotation` 包（注解，无依赖）。
- `helm-bom` 是 pom，无生产代码。
- japicmp 是 build plugin，不进 runtime classpath。
- `module-info.java`（阶段 2）只声明 JDK 内置 requires。

---

## 6. 测试策略

### 6.1 japicmp baseline 测试

§3.6 已详述配置。验收要点：

- 每个 public/spi 模块在 `verify` phase 运行 japicmp。
- baseline jar 版本由 `api.baseline.version` 控制。
- 报告输出到 `target/japicmp/japicmp.html`，CI artifact 保留。
- 破坏变更（非 `@Preview`）触发 build failure。

### 6.2 契约测试覆盖 SPI

既有：`RuntimeStoreContractTest`、`ModelProviderContractTest`、`SandboxContractTest`（均位于 `helm-core/src/test/java`，发布 test-jar）。

新增/拆分：

| 合约测试基类 | 覆盖 SPI | 形态 |
| --- | --- | --- |
| `SessionStoreContractTest` | `SessionStore` | 拆自 `RuntimeStoreContractTest` |
| `OperationStoreContractTest` | `OperationStore` | 拆自 `RuntimeStoreContractTest` |
| `WorkflowRunStoreContractTest` | `WorkflowRunStore` | 拆自 `RuntimeStoreContractTest` |
| `EventStoreContractTest` | `EventStore` | 拆自 `RuntimeStoreContractTest` |
| `RuntimeStoreContractTest` | `RuntimeStore`（聚合） | 保留，验证 facade 行为 |
| `MemoryStoreContractTest` | `MemoryStore` | 既有 |
| `ErrorCodeContractTest` | `ErrorCode` 注册表 | 新增，见 §6.3 |

### 6.3 ErrorCode 注册表测试

```java
package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies that every HelmException subclass uses a registered ErrorCode and codes are unique. */
class ErrorCodeContractTest {

    @Test
    void allRegisteredCodesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(seen.add(code.stable()))
                    .as("duplicate ErrorCode: %s", code)
                    .isTrue();
        }
    }

    @Test
    void helmExceptionCodeIsValidatedAgainstRegistry() {
        // Unknown codes are rejected at construction.
        assertThatThrownBy(() -> new TestHelmException("UNKNOWN_CODE", "m", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown HelmException code");
    }

    @Test
    void knownCodesArePreserved() {
        assertThat(new TestHelmException("AGENT_NOT_FOUND", "m", Map.of(), Map.of()).code())
                .isEqualTo("AGENT_NOT_FOUND");
    }

    private static final class TestHelmException extends HelmException {
        TestHelmException(String code, String message, Map<String, Object> details, Map<String, Object> dev) {
            super(code, message, details, dev);
        }
    }
}
```

### 6.4 HTTP 错误响应测试

`helm-http-core` 的 `HttpErrorContractTest`（已存在）增加用例：

- `HelmException` 子类的 `developerDetails` 不出现在 HTTP body。
- `code` 字段必为 `ErrorCode` 枚举值。
- `details` 字段值类型限定为 String/Number/Boolean/嵌套 Map/List（避免序列化对象泄漏）。

### 6.5 包归类测试（可选）

CI 加一个轻量检查脚本：扫描 `io.agent.helm.runtime.internal`、`io.agent.helm.engine.internal` 包，确认应用代码（`examples/`、`helm-cli`、`helm-spring-boot-starter`）未 import 这些包。可用 `grep` 或 `jdeps` 实现。

---

## 7. 验收标准

对齐 `docs/roadmap.md` 第 5 节 M0 验收。

### 7.1 M0 验收（直接对齐）

| roadmap M0 验收项 | 本组件达成方式 |
| --- | --- |
| 新增核心 API validation 测试 | 已完成（H-001），本组件不重复。 |
| 没有 adapter 需要依赖 runtime internals | §3.1.3 包重构 + §6.5 包归类检查脚本。 |
| public exception 都有稳定 code 和 safe details | §3.3 `ErrorCode` 注册表 + §6.3 `ErrorCodeContractTest` + §6.4 HTTP 错误响应测试。 |

### 7.2 M0 交付清单（本组件补齐）

| M0 交付项 | 状态 |
| --- | --- |
| 确认 Maven groupId/artifact 命名策略 | §3.4 决策 `io.agent.helm` |
| 写明 public package、SPI package、internal package 规则 | §3.1 三档分类 + 现有包归类表 |
| 明确 pre-1.0 API compatibility policy | §3.2 版本语义 + 破坏流程 + `@Preview`/`@Experimental` |
| 决定 `RuntimeStore` 是否拆分子接口 | §3.5 聚合 facade + 子接口 |
| public exception 都有稳定 code 和 safe details | §3.3 + §6.3 + §6.4 |

### 7.3 本组件专属验收

- [ ] `docs/contracts/error-codes.md` 创建并枚举全部 11 个既有 code。
- [ ] `ErrorCode` 枚举与 `ErrorCodeContractTest` 落地，`HelmException` 构造器校验 code。
- [ ] `io.agent.helm.core.annotation` 包新增 `@Preview`、`@Experimental`。
- [ ] `helm-core` / `helm-runtime` / `helm-agent-engine` 内部实现移入 `.internal` 子包。
- [ ] `RuntimeStore` 拆为 `SessionStore` + `OperationStore` + `WorkflowRunStore` + `EventStore` 子接口，`RuntimeStore extends` 全部；既有 `InMemoryRuntimeStore` / `JdbcRuntimeStore` 不改实现仍通过。
- [ ] 新增 4 个子接口的 `ContractTest` 基类，发布 test-jar。
- [ ] 根 `pom.xml` `<groupId>` 改为 `io.agent.helm`，所有子模块 pom 同步。
- [ ] 新增 `helm-bom` 模块，列入根 pom `<modules>`。
- [ ] `helm-core` / `helm-runtime` / `helm-agent-engine` pom 接入 japicmp 插件，`api.baseline.version` 占位。
- [ ] `docs/contracts/api-baseline.md` 创建，记录当前 baseline。
- [ ] `mvn verify` 全绿（含新测试与 japicmp 首次接入的 skip profile）。

---

## 8. 风险与未决项

### 8.1 风险

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 包重构（移入 `.internal`）可能破坏 examples / CLI / Spring Boot starter 的 import | 中 | 同 PR 内同步更新所有 in-tree 消费方；`mvn verify` 全绿作为门槛。 |
| `ErrorCode` 构造器校验对既有调用方造成运行期失败 | 低 | 既有 11 个 code 全部合法，校验只对未登记 code 报错；in-tree 调用方均合规。 |
| japicmp 首次接入无 baseline | 低 | 用 profile skip 首次，发布 `0.2.0` 后启用。 |
| `RuntimeStore extends` 多子接口后，部分静态分析工具可能误报"接口过宽" | 低 | 文档说明聚合是有意为之。 |
| `helm-bom` 模块引入可能让 release 流程变复杂 | 低 | BOM 仅做版本管理，无生产代码；release 脚本统一处理。 |

### 8.2 未决项

| 未决项 | 说明 | 决策时点 |
| --- | --- | --- |
| `ErrorCode` 是否纳入 HTTP status 映射 | 本组件只规定 code 稳定，HTTP status 由组件 #5/#6 细化。 | M6 authorizer / client SDK 落地时。 |
| `@Preview` 包豁免方式 | 阶段 1 用独立包 + japicmp excludes；阶段 2 是否切到自定义 filter 待定。 | 组件 #1 streaming API 落地时。 |
| JPMS 阶段 2 切换时点 | 与 1.0 发布前 release engineering（组件 #10）协同。 | 1.0 前。 |
| `details` 字段值类型是否强制 schema | 当前只规定白名单语义，未强制 JSON schema。是否引入 schema 校验待 adapter 反馈。 | M6 HTTP client SDK 落地时。 |
| `ProviderException` 多 code 模式是否推广到其他子类 | 当前其他子类单 code 够用。是否需要 `SandboxException` 区分 `SANDBOX_PATH_ESCAPE` / `SANDBOX_COMMAND_DENIED` 等待组件 #5/#9。 | M5 sandbox hardening 或 M6 authorizer 时。 |
| `helm-runtime-testkit` 模块归属 | roadmap M2 提到，本组件拆分子接口合约测试时是否需要独立 testkit 模块待定。 | M2 contract-first persistence 落地时。 |

---

## 9. 与其他组件的关系

本组件是后续 10 个组件设计的前置条件——所有 SPI 都依赖 package 分类、兼容性策略、exception 注册表的决策。

| 组件 | 依赖本组件的什么 | 备注 |
| --- | --- | --- |
| #1 Streaming API | `@Preview` 标注新 streaming 暴露 API；japicmp 豁免规则落地。 | streaming 形态未稳定，必须标 `@Preview`。 |
| #2 Engine hardening | engine events、tool validation 错误用 `ErrorCode` 注册；`TurnRunner` 异常不再裸 `IllegalStateException`，包入 `HelmException` 子类。 | 新增 `ENGINE_TIMEOUT`、`TOOL_INPUT_INVALID` 等 code 需登记。 |
| #3 JsonSchema 扩展 | JsonSchema 新增类型属 public API，需 baseline 跟踪。 | 形态基本稳定，可不标 `@Experimental`。 |
| #4 Memory 语义检索 | `MemoryStore` 标 `@Experimental`；`search` 替换点不破坏既有 `MemoryStoreContractTest`。 | §3.5.4 不合并入 RuntimeStore。 |
| #5 Authorizer / SecurityContext | `HelmAuthorizer` 新 SPI；`HelmSecurityContext` public API；HTTP 错误响应映射 `ErrorCode` → HTTP status。 | 授权失败新增 `UNAUTHORIZED` / `FORBIDDEN` code 需登记。 |
| #6 HTTP Client SDK | 客户端依赖 public API + `HelmErrorResponse` DTO；`details` schema 在此细化。 | |
| #7 Rate limiting | admission 错误新增 `RATE_LIMITED` / `OPERATION_REJECTED` code 需登记；queue API 标 `@Preview`。 | |
| #8 Metrics / OpenTelemetry | `RuntimeEventObserver` 标 `@Experimental`；`developerDetails` 默认 redaction 策略在此组件落地。 | |
| #9 Durable scale runtime | lease/journal/cancellation API 标 `@Preview`；新错误 code（`LEASE_LOST`、`RECOVERY_FAILED`）登记。 | |
| #10 Release engineering | BOM 模块、japicmp CI、`api-baseline.md`、`CHANGELOG.md` 在此组件奠基；groupId 迁移与 release 流程协同。 | license 决策仍属 #10。 |

### 9.1 命名对齐

本组件规定的命名约束适用于所有后续组件：

- 新 SPI 接口放 `helm-core` 对应子包（如 `core.security`、`core.admission`）。
- 新 `HelmException` 子类放 `helm-core/src/main/java/io/agent/helm/core/error/`，code 在 `ErrorCode` 枚举登记。
- 新 `ContractTest` 基类放 `helm-core/src/test/java/...`，发布 test-jar。
- 新 adapter 模块只依赖 `helm-core` 的 public/spi 包，不依赖 `helm-runtime` / `helm-agent-engine` 的 `.internal` 包。

### 9.2 时序对齐

按 `docs/design/README.md` 第 2 节依赖图，本组件（#11）应在 #2 engine hardening、#3 json schema、#8 metrics/otel、#7 rate limiting 之前落地，因为这些组件会新增 SPI / ErrorCode。#5 authorizer、#6 client SDK 也依赖本组件的 HTTP 错误响应形态。

建议本组件作为 M0 收尾的最后一个 slice，落地后即可关闭 M0 全部未勾选项与第 7 节两个 blocker。
