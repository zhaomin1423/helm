# M0/M1 First Slice Findings

## Roadmap Scope

`docs/roadmap.md` recommends a small M0/M1 closure before adapter work:

1. `AgentConfig` validation.
2. `OperationStatus` / `WorkflowRunStatus` enum.
3. Runtime inspection APIs.
4. Basic event taxonomy.
5. `OperationHandle` and `dispatch` admission API.
6. Runtime store contract documentation.

## Reusable Patterns Found

- `AgentConfig` is a public record with a simple builder in `helm-core/src/main/java/io/agent/helm/core/agent/AgentConfig.java`.
- `ModelRef` uses `Objects.requireNonNull` and `IllegalArgumentException` style validation.
- Core records use compact constructors and defensive copies via `List.copyOf` / `Map.copyOf`.
- Runtime tests use JUnit 5 + AssertJ, deterministic `FakeProvider`, and local test fakes.
- `WorkflowRunHandle` is a tiny record and is the best shape precedent for `OperationHandle`.
- `InMemoryRuntimeStore` already has sorted package-private `operations()` and `workflowRuns()` helpers; these can become public store list methods.
- Runtime events already pass through `EventRedactor.redact(...)` before storage.

## Current Gaps

- `AgentConfig` allows missing model, null instructions, duplicate tool names, and ambiguous tool names.
- `OperationRecord.status` and `WorkflowRunRecord.status` are raw strings.
- `RuntimeEventRecord.type` is a raw string and runtime code emits string literals.
- `AgentRuntime` has no `getOperation`, `getOperationEvents`, `dispatch`, or `OperationHandle`.
- `WorkflowRuntime` has no `getRun`, `listRuns`, or `getRunEvents`.
- `RuntimeStore` lacks listing methods and written method invariants.
- `docs/contracts/runtime-store.md` does not exist yet.

## Safety/Scope Notes

- Do not add provider/JDBC/HTTP/CLI/Spring modules in this slice.
- Do not implement durable async workers, lease/recovery, or queues.
- Do not fully implement sandbox defaults before M5; document null sandbox policy instead.
