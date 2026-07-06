# AGENTS.md

This file gives coding agents the project-specific context needed to work in this repository.

## Project overview

Helm is a Java 21 Agent Harness Framework. The codebase is pre-1.0: all planned modules except `helm-sandbox-remote` are implemented and pass tests, but APIs may still evolve before 1.0. The codebase is organized as 21 Maven modules (17 `helm-*` modules plus 4 `examples/`), under the package namespace `io.agent.helm`.

Module groups:

- Core / engine / runtime: `helm-core` (API + SPI contracts, structured errors, messages, model provider/tool/workflow/sandbox/store/event types), `helm-agent-engine` (agent loop, turn runner, tool-call orchestration, streaming, context management), `helm-runtime` (registries, fake provider, in-memory runtime store, agent/workflow runtime, event redaction, admission + rate limiting, durable queue).
- Providers: `helm-provider-openai`, `helm-provider-anthropic`.
- Sandbox: `helm-sandbox-local` (no `helm-sandbox-remote` yet).
- HTTP / CLI / Spring / client SDK: `helm-http-core`, `helm-http-servlet`, `helm-cli`, `helm-spring-boot-starter`, `helm-client`.
- Persistence: `helm-persistence-jdbc` (JDBC store, schema migrations, event persistence, optimistic concurrency control).
- Observability: `helm-observability-logging`, `helm-observability-opentelemetry` (metrics + tracing `RuntimeEventObserver`).
- Memory: `helm-memory-semantic` (`SemanticMemoryStore` decorator, in-memory `EmbeddingStore`, `FakeEmbeddingProvider`).
- Test kit and BOM: `helm-runtime-testkit` (test fixtures for `AgentRuntime` + `FakeProvider` + `InMemoryRuntimeStore`), `helm-bom`.
- Examples: `coding-workflow`, `memory-session-example`, `spring-boot-example`, `external-consumer`.

Key current capabilities (mostly `@Preview` / pre-1.0):

- Durable dispatch: in-memory `WorkQueue` with lease claiming and `LeaseManager` recovery for expired leases. Turn journal is SPI-only; stream-chunk recovery, durable cancellation, provider routing, and remote sandbox are not yet implemented.
- Streaming: `promptStream` exposes streaming responses and persists streamed sessions with tool-call messages.
- Admission control: `HelmAuthorizer` + `SecurityContext`, `RateLimiter` SPI (basic) on the admission path.
- Memory / session management: `MemoryStore` SPI with `save_memory` tool, agent-scoped long-term memory, session lifecycle (list/inspect/reset), `maxSessionMessages` history trimming.
- Observability: structured event bus, logging observer, OTel observer with metrics + tracing.
- JDBC persistence with optimistic concurrency control and idempotent event writes.

## Build and verification

Use JDK 21.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

`mvn verify` runs:

- compilation for all modules,
- unit tests (~809 tests across 21 modules),
- Spotless format checks.

For formatting only:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn spotless:apply
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn spotless:check
```

## Coding style

- Keep the core runtime independent of Spring, Servlet, CLI, provider SDKs, JDBC, and logging adapters.
- Keep `helm-core` limited to stable API/SPI contracts and small value types.
- Put runtime orchestration and persistence behavior in `helm-runtime`.
- Put model/tool turn execution logic in `helm-agent-engine`.
- Prefer small interfaces and immutable records/value objects.
- Wrap framework failures in structured `HelmException` subclasses where appropriate. `HelmException` supports cause-chaining via a wrapped `Throwable`.
- Tools receive an expanded `ToolContext` carrying `securityContext`, `sandbox`, `clock`, and `logger` — do not reach for static/global instances.
- Do not introduce real provider credentials or network-dependent tests.
- Use deterministic tests with `FakeProvider` or local in-memory fakes.

## Formatting

Spotless is the source of truth for formatting. Run `mvn spotless:apply` before committing changes. Do not hand-format code against Spotless output.

## Testing expectations

Before considering a code change complete, run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

If verification cannot run because JDK 21 is unavailable, report that explicitly and do not claim the change is verified.

## Documentation

- MVP design: `docs/helm-mvp-design.md`
- Milestone 1 plan and completion status: `docs/superpowers/plans/2026-06-28-helm-mvp-milestone-1.md`
- Milestone 1 API example: `docs/examples/milestone-1-agent-workflow.md`
- Coding workflow design example: `examples/coding-workflow/`

## Git workflow

Follow conventional commit messages (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`). Commit only when asked; the repository has many commits rather than a single foundation commit.
