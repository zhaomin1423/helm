# M0/M1 First Slice Task Plan

## Goal

Implement the next recommended roadmap slice from `docs/roadmap.md`: stabilize core runtime contracts and add operation/workflow inspection before provider, JDBC, HTTP, CLI, or Spring work.

## Current Phase

- Status: complete
- Active phase: Phase 9 — verification and code review complete.

## Phases

| Phase | Status | Notes |
| --- | --- | --- |
| 1. Spec and planning files | complete | Created durable spec, findings, and progress files. |
| 2. H-001 AgentConfig validation | complete | Added validation tests and implementation. |
| 3. H-002 status enums | complete | Replaced bare operation/workflow status strings in records/runtime. |
| 4. H-004 event taxonomy | complete | Added canonical `RuntimeEventType` constants. |
| 5. H-003 inspection APIs | complete | Added runtime/store inspection and list APIs. |
| 6. H-005 dispatch/OperationHandle | complete | Added synchronous MVP dispatch with inspectable operation. |
| 7. H-006 RuntimeStore contract doc | complete | Documented invariants and M2 deferrals. |
| 8. Roadmap/progress updates | complete | Marked H-001 through H-006 complete in roadmap. |
| 9. Verification and review | complete | `mvn verify` passed; code-reviewer found no blocking issues. |

## Key Decisions

- Keep Maven coordinates as `io.agent:helm` for this slice.
- Keep `RuntimeStore` as a single facade; defer store splitting to M2.
- `AgentConfig` validation lives in the compact constructor.
- `AgentConfig.instructions(null)` normalizes to `""`.
- `AgentConfig.sandbox()` remains nullable and means no sandbox configured.
- Use `OperationStatus` and `WorkflowRunStatus` enums for persisted record status.
- Keep `RuntimeEventRecord.type()` as `String`; add `RuntimeEventType` as canonical type source.
- `dispatch(...)` remains synchronous for MVP and returns `OperationHandle(operationId, status)`.

## Errors Encountered

| Error | Attempt | Resolution |
| --- | --- | --- |
| `Read` calls with empty `pages` parameter failed | During plan exploration | Retried with a valid `pages` value for non-PDF reads. |
| `mvn -pl helm-runtime test` could not resolve sibling module artifacts | First targeted runtime test | Re-ran with `-pl helm-runtime -am test` to include required reactor modules. |
| Initial `mvn verify` failed Spotless check | Full verification | Ran `mvn spotless:apply`, then re-ran `mvn verify` successfully. |
