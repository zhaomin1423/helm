# Helm MVP Development Spec

Source design: [`docs/helm-mvp-design.md`](../../helm-mvp-design.md)

## 1. Purpose

This spec turns the Helm MVP design into an implementation-ready development plan for a Maven-built Java project.

Helm is a Java Agent Harness Framework. The MVP must deliver a core-first runtime that can define agents, workflows, tools, skills, sandboxes, providers, sessions, operations, events, and persistence without binding the core to Spring, Servlet, Netty, or any provider SDK.

Confirmed project constraints:

1. Build system: Maven multi-module.
2. Java package namespace: `io.agent.helm`.
3. Java version: 21.
4. First delivery style: full MVP phased spec, with Milestone 1 detailed enough to implement directly.

## 2. Non-goals

The MVP does not include distributed scheduling, a built-in multi-tenant authorization system, a plugin marketplace, production-grade container isolation, a first-party channel ecosystem, or exactly-once durable execution across processes.

The Maven project must keep those concerns out of the public MVP API so they can be added later through adapters, policies, or external infrastructure.

## 3. Architecture

### 3.1 Maven module layout

The repository should use this Maven module structure:

```text
helm/
  pom.xml
  helm-core/
  helm-agent-engine/
  helm-runtime/
  helm-http-core/
  helm-http-servlet/
  helm-cli/
  helm-spring-boot-starter/
  helm-provider-openai/
  helm-provider-anthropic/
  helm-sandbox-local/
  helm-persistence-jdbc/
  helm-observability-logging/
  examples/
```

The root `pom.xml` is the aggregator and parent. It owns Java version, dependency versions, plugin versions, compiler settings, test settings, formatting-neutral build defaults, and module declarations.

All production Java source uses the `io.agent.helm` namespace. Module-internal packages should follow module responsibility, for example:

```text
io.agent.helm.core
io.agent.helm.engine
io.agent.helm.runtime
io.agent.helm.http.core
io.agent.helm.http.servlet
io.agent.helm.cli
io.agent.helm.spring
io.agent.helm.provider.openai
io.agent.helm.provider.anthropic
io.agent.helm.sandbox.local
io.agent.helm.persistence.jdbc
io.agent.helm.observability.logging
```

### 3.2 Dependency direction

Dependencies must preserve the core-first design:

```text
helm-core
  <- helm-agent-engine
  <- helm-runtime
  <- helm-http-core
  <- helm-http-servlet
  <- helm-cli
  <- helm-spring-boot-starter
  <- helm-provider-*
  <- helm-sandbox-local
  <- helm-persistence-jdbc
  <- helm-observability-logging
```

Allowed dependencies:

1. `helm-core` has no dependency on runtime, engine, HTTP, CLI, Spring, Servlet, provider SDKs, JDBC, or logging adapters.
2. `helm-agent-engine` depends on `helm-core` only.
3. `helm-runtime` depends on `helm-core` and `helm-agent-engine`.
4. `helm-http-core` depends on `helm-core` and `helm-runtime`, but not Servlet, Spring, or Netty.
5. `helm-http-servlet` depends on `helm-http-core` and Servlet APIs.
6. `helm-cli` depends on runtime and selected local adapters needed for local development.
7. `helm-spring-boot-starter` depends on Spring Boot and Helm modules, but no core module depends on Spring.
8. Provider, sandbox, persistence, and observability modules implement SPI from `helm-core` or integration points from `helm-runtime`.

## 4. Module responsibilities

### 4.1 `helm-core`

`helm-core` defines stable public API, domain model, SPI, configuration types, structured errors, and shared value objects.

Required API families:

1. Agent API: `AgentDefinition`, `AgentConfig`, `AgentContext`, `AgentInstanceId`.
2. Workflow API: `WorkflowDefinition<I, O>`, `WorkflowConfig`, `WorkflowContext<I>`, `WorkflowRun`, `WorkflowRunHandle`.
3. Tool API: `Tool<I, O>`, `ToolContext`, `ToolCall`, `ToolResult`.
4. Skill API: `SkillDefinition`, skill resource metadata, classpath/config-directory references.
5. Sandbox SPI: `Sandbox`, `SandboxFileSystem`, `SandboxShell`, sandbox command and file result types.
6. Provider SPI: `ModelProvider`, `ModelRef`, `ModelRequest`, `ModelResponse`, `ModelStreamEvent`, `TokenUsage`.
7. Runtime store SPI: session, operation, workflow run, and event persistence contracts.
8. Message model: `HelmMessage`, content blocks, roles, tool-call blocks, tool-result blocks.
9. Type support: `TypeDescriptor<T>` and `JsonSchema`.
10. Error model: `HelmException` and concrete structured exceptions.

Acceptance criteria:

1. Public interfaces compile independently of all adapter modules.
2. Generic workflow and tool input/output types can be represented without losing generic type information.
3. All framework exceptions expose stable `code`, safe `details`, and developer-only `developerDetails`.
4. Unit tests cover type descriptors, structured errors, model refs, and simple schema generation.

### 4.2 `helm-agent-engine`

`helm-agent-engine` is Helm's internal agent/model execution layer. It replaces the Pi responsibilities described in the design with first-party Java types.

Required components:

1. `AgentEngine`: entry point for a prepared operation.
2. `AgentLoop`: repeats model and tool turns until a terminal assistant response or stop condition.
3. `TurnRunner`: builds model requests, consumes stream events, and produces turn results.
4. `ModelStreamNormalizer`: converts provider stream events into stable engine events.
5. `ToolCallOrchestrator`: validates and executes requested tools through a narrow executor interface.
6. `ContextManager`: detects context overflow and applies MVP compaction decisions.
7. `ContextOverflowException`: normalized overflow signal.

The engine must not discover providers, open sessions, persist state, expose HTTP DTOs, or know about Spring/Servlet/CLI. It receives prepared dependencies from runtime.

Acceptance criteria:

1. A fake streaming provider can produce a terminal assistant response.
2. A fake streaming provider can request a tool call and consume a tool result in a later turn.
3. Tool execution failures are wrapped as structured tool errors.
4. Context overflow is classified consistently.
5. Event hooks are emitted for model request/response, tool start/complete, and error.

### 4.3 `helm-runtime`

`helm-runtime` owns harness lifecycle, sessions, operations, workflow runs, event bus integration, and runtime store use.

Required components:

1. `AgentRuntime`
2. `WorkflowRuntime`
3. `AgentHarness`
4. `AgentSession`
5. `HarnessFactory`
6. `ProviderRegistry`
7. `ToolRegistry`
8. `SkillRegistry`
9. `SandboxFactory`
10. `OperationRunner`
11. `RuntimeEventBus`
12. `InMemoryRuntimeStore`
13. `FakeProvider`

Runtime rules:

1. Session identity is `agentName + instanceId + sessionName`.
2. A session has a monotonically increasing version.
3. MVP guarantees at most one active operation per session.
4. If a session already has an active operation, MVP may return `SESSION_BUSY`.
5. Session state read, operation append, message append, and version update must occur inside one consistency boundary.
6. Workflow runs and agent operations are separate concepts with separate ID namespaces.
7. Runtime events are ordered by sequence within an operation or workflow run.

Acceptance criteria:

1. `AgentRuntime.prompt` can run a fake provider through the engine and return `PromptResult`.
2. `AgentRuntime.dispatch` admits an operation and returns `OperationHandle`; MVP may execute synchronously internally.
3. `WorkflowRuntime.invoke` creates a run, executes a workflow, and stores result or structured error.
4. `AgentSession` can create and resume named sessions from `RuntimeStore`.
5. `InMemoryRuntimeStore` supports deterministic unit and integration tests.

### 4.4 HTTP, CLI, Spring, provider, sandbox, persistence, and observability modules

These modules are delivered after the core runtime loop is stable.

`helm-http-core` defines framework-neutral request/response DTOs, route specs, error DTOs, and `HelmHttpHandler`.

`helm-http-servlet` mounts `helm-http-core` onto Servlet containers. It must support optional `HelmAuthorizer` and must not treat agent instance IDs as authorization.

`helm-cli` provides local development commands:

```bash
helm dev
helm run <workflowName> --input '<json>'
helm agents
helm workflows
helm inspect operation <operationId>
helm inspect workflow-run <runId>
```

`helm-spring-boot-starter` auto-configures runtime, workflow runtime, store, provider registry, observer, and HTTP adapter, and discovers Helm beans.

`helm-provider-openai` and `helm-provider-anthropic` implement `ModelProvider` using streaming-first SPI.

`helm-sandbox-local` provides `InMemorySandbox` and `LocalSandbox`. Local shell is disabled by default and must enforce workspace normalization, timeout, output limit, fixed working directory, and environment allowlist.

`helm-persistence-jdbc` provides JDBC runtime store and migrations.

`helm-observability-logging` provides a logging observer that redacts sensitive payloads by default.

## 5. Data flow contracts

### 5.1 Agent prompt

```text
Java or HTTP caller
  -> AgentRuntime.prompt
  -> resolve AgentDefinition
  -> create AgentHarness
  -> open AgentSession
  -> create Operation
  -> AgentEngine.run
  -> TurnRunner builds ModelRequest
  -> ModelProvider streams ModelStreamEvent values
  -> ToolCallOrchestrator executes requested tools
  -> repeat until terminal response or stop condition
  -> store messages, operation result, and events
  -> return PromptResult
```

### 5.2 Agent dispatch

```text
External event caller
  -> AgentRuntime.dispatch
  -> resolve persistent agent instance and session
  -> admit operation
  -> return OperationHandle
  -> execute now or later behind the same operation abstraction
```

The MVP may execute dispatch synchronously internally, but the public API must behave like operation admission so future async execution does not break compatibility.

### 5.3 Workflow invoke

```text
Java, HTTP, or CLI caller
  -> WorkflowRuntime.invoke
  -> create WorkflowRun
  -> initialize workflow harness
  -> run WorkflowDefinition
  -> use agent session, tool, skill, or sandbox as needed
  -> validate output
  -> store result or structured error
  -> return run id and optional result
```

Workflow states:

```text
PENDING -> RUNNING -> SUCCEEDED
                  -> FAILED
```

`CANCELLED` is not part of MVP.

## 6. Persistence contract

MVP provides:

1. `InMemoryRuntimeStore` for tests and examples.
2. `JdbcRuntimeStore` for local or server persistence.

The store must support:

1. Session recovery by agent name, instance ID, and session name.
2. Operation lookup by operation ID.
3. Workflow run lookup by run ID.
4. Runtime event lookup by operation ID or workflow run ID.
5. Structured error persistence, not just error message strings.
6. Ordered event reads using sequence values.

Session persistence is snapshot-first. `agent_sessions.state_json` is the recovery source for messages, compaction summary, and session metadata. Runtime events are observability history and are not the sole recovery source.

## 7. Security and redaction

Security defaults:

1. HTTP routes are disabled unless explicitly enabled.
2. `helm dev` listens on `127.0.0.1` by default.
3. Agent instance ID is not an authorization primitive.
4. Provider credentials are read only server-side.
5. Tools expose narrow typed capabilities.
6. Skill file access is restricted to the registered skill directory.
7. Local shell is disabled by default.
8. Sandbox file access must normalize and enforce workspace root.
9. Runtime events must pass through a redactor before storage or logging.
10. Request body size must be limited.
11. Provider and tool errors must not leak credentials.

Redaction rules:

1. Provider headers, API keys, environment variables, and marked secret parameters are never stored in event payloads.
2. `developerDetails` are not returned to external HTTP callers by default.
3. Logging observer records metadata and summaries by default, not full prompt/model/tool payloads.

## 8. Error model

All framework errors inherit from:

```java
public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;
}
```

Required error categories:

| Code | Exception | Meaning |
| --- | --- | --- |
| `AGENT_NOT_FOUND` | `AgentNotFoundException` | Requested agent is not registered. |
| `WORKFLOW_NOT_FOUND` | `WorkflowNotFoundException` | Requested workflow is not registered. |
| `PROVIDER_NOT_FOUND` | `ProviderNotFoundException` | No provider supports the model reference. |
| `TOOL_EXECUTION_FAILED` | `ToolExecutionException` | Tool execution failed or produced invalid output. |
| `SANDBOX_ERROR` | `SandboxException` | File system or shell operation failed. |
| `VALIDATION_FAILED` | `ValidationException` | Input, output, schema, or config validation failed. |
| `PERSISTENCE_ERROR` | `PersistenceException` | Runtime store operation failed. |
| `SESSION_BUSY` | `SessionBusyException` | Session already has an active operation. |
| `CONTEXT_OVERFLOW` | `ContextOverflowException` | Provider or engine detected context overflow. |

HTTP responses must expose stable codes and safe details only.

## 9. Milestones

### 9.1 Milestone 1: Core runtime

Goal: deliver a Maven project with the core Java API, engine, runtime, fake provider, in-memory store, and tests proving the minimal agent prompt/workflow path works.

Deliverables:

1. Root Maven parent project.
2. `helm-core`.
3. `helm-agent-engine`.
4. `helm-runtime`.
5. `FakeProvider`.
6. `InMemoryRuntimeStore`.
7. Unit and integration tests for the core runtime loop.

Implementation tasks:

1. Create parent `pom.xml` with Java 21, Maven Surefire, compiler settings, dependency management, and module list for Milestone 1 modules.
2. Create `helm-core` with public interfaces, value objects, message/content model, model provider SPI, tool SPI, workflow SPI, sandbox SPI, runtime store SPI, JSON schema model, type descriptor, and structured errors.
3. Create `helm-agent-engine` with engine request/result types, agent loop, turn runner, stream normalizer, tool-call orchestration, context manager, stop conditions, and event callbacks.
4. Create `helm-runtime` with runtime APIs, harness/session/operation/workflow lifecycle, registries, event bus, fake provider, and in-memory store.
5. Add tests for each public contract and the end-to-end fake-provider prompt flow.
6. Add documentation examples showing a minimal agent and workflow using package `io.agent.helm`.

Milestone 1 acceptance criteria:

1. `mvn test` succeeds for all Milestone 1 modules.
2. A fake provider terminal response can be returned through `AgentRuntime.prompt`.
3. A fake provider tool-call sequence can execute a registered typed tool and return the final assistant response.
4. `WorkflowRuntime.invoke` can run a typed workflow and persist a run record.
5. Session recovery works with `InMemoryRuntimeStore`.
6. `SESSION_BUSY` or equivalent single-active-operation behavior is covered by tests.
7. Structured errors serialize without exposing developer-only details.

### 9.2 Milestone 2: Provider, skill, and sandbox

Goal: add real provider adapters, skill loading, and sandbox implementations.

Deliverables:

1. `helm-provider-openai`.
2. `helm-provider-anthropic`.
3. Skill loading from classpath and configured directories.
4. `InMemorySandbox`.
5. `LocalSandbox`.

Acceptance criteria:

1. Provider contract tests pass against mocked streaming responses.
2. Skill loading rejects paths outside the skill root.
3. Local sandbox rejects path traversal and shell execution unless explicitly enabled.
4. Shell command timeout and output limits are covered by tests.

### 9.3 Milestone 3: HTTP and CLI

Goal: expose runtime capabilities through framework-neutral HTTP handlers, Servlet adapter, and local CLI.

Deliverables:

1. Agent prompt route.
2. Agent dispatch route.
3. Workflow invoke route.
4. Operation inspection routes.
5. Workflow run inspection routes.
6. `helm dev`.
7. `helm run`.
8. `helm inspect`.

Acceptance criteria:

1. HTTP DTOs do not depend on Servlet or Spring.
2. Servlet adapter can mount the framework-neutral handler.
3. HTTP errors return stable codes and safe details.
4. CLI can run a workflow using local configuration and fake provider.
5. Inspection commands show operation/run status and ordered events.

### 9.4 Milestone 4: Spring Boot starter

Goal: provide Spring Boot integration without moving Spring concerns into core modules.

Deliverables:

1. Runtime auto-configuration.
2. Provider/store/observer auto-configuration.
3. Bean discovery for agents, workflows, tools, and providers.
4. HTTP route mounting.
5. Spring Boot example.

Acceptance criteria:

1. A Spring Boot test application can define an agent/workflow/tool as beans.
2. `application.yml` binds Helm provider, HTTP, sandbox, and persistence settings.
3. HTTP exposure remains opt-in.
4. Core modules remain Spring-free.

### 9.5 Milestone 5: JDBC and observability

Goal: add durable JDBC persistence and production-usable logging observer.

Deliverables:

1. `JdbcRuntimeStore`.
2. Schema migrations.
3. Runtime event persistence.
4. Logging observer.

Acceptance criteria:

1. JDBC round-trip tests cover sessions, operations, workflow runs, and events.
2. Optimistic locking protects session version updates.
3. Runtime events preserve order by sequence.
4. Logging observer redacts secrets and developer-only details by default.

## 10. Testing strategy

Use JUnit 5 and AssertJ for unit and integration tests.

Milestone 1 test coverage:

1. Agent config initialization.
2. Workflow state transitions.
3. Session creation and recovery.
4. Type descriptor generic preservation.
5. Tool schema generation.
6. Tool execution and error wrapping.
7. Agent loop terminal response behavior.
8. Agent loop tool-call ordering.
9. Model stream normalization.
10. Context overflow classification.
11. Session busy or optimistic-lock behavior.
12. Provider registry and `ModelRef` parsing.
13. In-memory runtime store behavior.
14. Structured error serialization.
15. Event redaction.

Later integration and contract tests:

1. Fake-provider HTTP agent prompt.
2. Fake-provider HTTP workflow invoke.
3. Agent operation inspection.
4. Workflow run inspection.
5. JDBC runtime store round trip.
6. Spring Boot auto-configuration.
7. CLI workflow execution.
8. `ModelProvider` SPI contract.
9. `Sandbox` SPI contract.
10. `RuntimeStore` SPI contract.
11. `HelmAuthorizer` integration.
12. HTTP error response contract.

## 11. Development order

Recommended order:

1. Create Maven parent and Milestone 1 modules.
2. Implement `helm-core` value objects, interfaces, and structured errors.
3. Implement `TypeDescriptor` and minimal `JsonSchema` support.
4. Implement model stream and message/content model.
5. Implement `FakeProvider`.
6. Implement `helm-agent-engine` terminal response path.
7. Add tool-call orchestration path.
8. Implement `InMemoryRuntimeStore`.
9. Implement `AgentRuntime.prompt`.
10. Implement `AgentSession` recovery.
11. Implement `WorkflowRuntime.invoke`.
12. Add event bus and redaction.
13. Fill Milestone 1 tests and examples.
14. Proceed through later milestones only after Milestone 1 tests are green.

## 12. Build and verification

The primary verification command is:

```bash
mvn test
```

During development, run targeted module tests where possible:

```bash
mvn -pl helm-core test
mvn -pl helm-agent-engine test
mvn -pl helm-runtime test
mvn -pl helm-runtime -am test
```

Release readiness for each milestone requires the full Maven test suite to pass from the repository root.

## 13. Open decisions intentionally deferred

These decisions are not required before Milestone 1 implementation:

1. Final publishing coordinates beyond the package namespace `io.agent.helm`.
2. Whether HTTP later adds WebFlux or Netty adapters.
3. Exact OpenAI and Anthropic SDK choices.
4. Final CLI packaging method.
5. Final database migration tool version and supported databases.
6. OpenTelemetry integration shape.
7. Production sandbox isolation mechanism.

The public API should avoid choices that make these decisions hard to change later.
