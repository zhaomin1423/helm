# M0/M1 First Slice Spec

## Purpose

This spec defines the next Helm production-readiness slice from `docs/roadmap.md`: stabilize core API/runtime boundaries and add inspection-capable lifecycle records before implementing external adapters.

## In Scope

- `AgentConfig` validation.
- Typed operation and workflow run statuses.
- Canonical runtime event type taxonomy for current and near-term event categories.
- Operation and workflow run inspection APIs.
- Minimal runtime store listing APIs.
- Synchronous MVP dispatch API returning an operation handle.
- Runtime store contract documentation.
- Roadmap/progress updates for completed work.

## Out of Scope

- Real LLM providers.
- JDBC/Flyway persistence.
- HTTP/Servlet/client SDK.
- CLI and Spring Boot starter.
- Durable async queues, leases, retries, cancellation, or recovery.
- Full sandbox implementation or local shell policy enforcement.

## API Contracts

### AgentConfig

- `model` is required.
- `instructions` is never stored as `null`; missing or explicit `null` instructions become `""`.
- `tools` is required and defensively copied.
- Tool entries are non-null.
- Tool names are non-null, non-blank, and unique within an agent config.
- `sandbox` may be `null`; `null` means no sandbox configured in this slice.

### Status Records

- `OperationRecord.status()` returns `OperationStatus`.
- `WorkflowRunRecord.status()` returns `WorkflowRunStatus`.
- Initial enum values: `RUNNING`, `SUCCEEDED`, `FAILED`.

### Events

- `RuntimeEventRecord.type()` remains a string for compatibility and future custom event names.
- `RuntimeEventType` defines canonical emitted types:
  - `operation.started`, `operation.succeeded`, `operation.failed`
  - `workflow.started`, `workflow.succeeded`, `workflow.failed`
- Runtime must use `RuntimeEventType` constants instead of ad hoc literals.
- Event payloads are redacted before storage.

### Inspection

- `AgentRuntime.getOperation(operationId)` returns an optional operation record.
- `AgentRuntime.getOperationEvents(operationId)` returns ordered events or an empty list.
- `WorkflowRuntime.getRun(runId)` returns an optional workflow run record.
- `WorkflowRuntime.listRuns()` returns workflow runs sorted by `createdAt` ascending.
- `WorkflowRuntime.getRunEvents(runId)` returns ordered events or an empty list.

### Dispatch

- `AgentRuntime.dispatch(AgentPromptRequest)` is synchronous in this MVP slice.
- It returns `OperationHandle(operationId, status)` on success.
- Prompt output is inspected through `getOperation(operationId).output()`.
- If execution fails, the failed operation record and failed event are stored before the exception is rethrown.

## Acceptance Criteria

- `mvn verify` passes.
- H-001 through H-006 have tests or documentation as appropriate.
- No core operation/workflow run record stores status as a bare string.
- Runtime inspection can read terminal success and failure records/events.
- `dispatch` creates an inspectable operation and does not return/mix workflow run ids.
- Runtime store contract doc exists at `docs/contracts/runtime-store.md`.
