# RuntimeStore Contract

`RuntimeStore` is Helm's persistence SPI for runtime state. This contract describes the behavior required by the current in-memory store and future durable stores.

## Responsibilities

A store persists four kinds of records:

1. Agent session state.
2. Agent operation records.
3. Workflow run records.
4. Runtime event records.

The store is infrastructure-only. Runtime code is responsible for mapping errors to safe details and redacting event payloads before persistence.

## Identity

- Session ids are runtime-defined and currently derived from agent name, instance id, and session name.
- Operation ids identify agent operations and are distinct from workflow run ids.
- Workflow run ids identify finite workflow invocations and must not be reused as operation ids.
- Event ids should be unique within a store.

## Save and Load Semantics

- `saveSession`, `saveOperation`, and `saveWorkflowRun` replace the current record for the same id.
- `loadSession`, `loadOperation`, and `loadWorkflowRun` return the latest record visible to the store.
- Unknown ids return `Optional.empty()` for record lookups.
- Returned lists are snapshots and must be safe for callers to inspect without mutating store internals.
- Runtime implementations must persist terminal operation/run records before returning success or allowing an execution exception to escape.

## Listing Semantics

- `listOperations()` returns operation records sorted by `createdAt` ascending.
- `listWorkflowRuns()` returns workflow run records sorted by `createdAt` ascending.
- Pagination, filtering, and tenant scoping are deferred to later HTTP/JDBC work.

## Event Semantics

- `appendEvent` stores one runtime event record.
- `eventsForOperation(operationId)` returns only events whose `operationId` matches and sorts them by `sequence` ascending.
- `eventsForWorkflowRun(workflowRunId)` returns only events whose `workflowRunId` matches and sorts them by `sequence` ascending.
- Event sequence numbers are scoped to the operation or workflow run being inspected.
- Unknown ids return an empty event list.

## Status Semantics

- Operation status is represented by `OperationStatus`.
- Workflow run status is represented by `WorkflowRunStatus`.
- Current terminal statuses are `SUCCEEDED` and `FAILED`; `RUNNING` is non-terminal.

## Safety

- Store implementations must not add developer-only details to operation errors, workflow errors, or event payloads.
- Store implementations should persist payloads exactly as provided by runtime after redaction.
- Provider credentials and application secrets must not be introduced by persistence adapters.

## Concurrency and Durability

- The in-memory implementation is best-effort thread-safe for local/runtime tests.
- Optimistic session version checks, migration/version checks, leases, queue-backed admission, and recovery semantics are out of scope for this slice.
- Those durable guarantees belong to M2 persistence and M11 durable-scale runtime work.

## Future Split Decision

`RuntimeStore` remains one facade in this slice. Splitting it into `SessionStore`, `OperationStore`, `WorkflowRunStore`, and `EventStore` remains a documented M2 design decision.
