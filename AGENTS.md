# AGENTS.md

This file gives coding agents the project-specific context needed to work in this repository.

## Project overview

Helm is a Java 21 Agent Harness Framework. The current codebase contains the Milestone 1 foundation:

- `helm-core`: public API and SPI contracts, structured errors, messages, model provider SPI, tool, workflow, sandbox, store, and event types.
- `helm-agent-engine`: prepared model/tool turn execution and agent loop behavior.
- `helm-runtime`: registries, fake provider, in-memory runtime store, agent prompt runtime, workflow runtime, event redaction, and tests.

The package namespace for production Java code is `io.agent.helm`.

## Build and verification

Use JDK 21.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn verify
```

`mvn verify` runs:

- compilation for all modules,
- unit tests,
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
- Wrap framework failures in structured `HelmException` subclasses where appropriate.
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

The repository currently keeps the initial foundation in a single commit. If asked to preserve this shape, amend changes into the existing commit instead of creating additional commits.
