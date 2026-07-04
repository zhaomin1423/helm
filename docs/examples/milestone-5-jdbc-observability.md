# Milestone 5 JDBC Persistence and Observability

Milestone 5 adds a durable JDBC `RuntimeStore` with Flyway migrations and a logging runtime
observer. Session, operation, workflow run, and event history survive process restarts.

## JDBC store

`helm-persistence-jdbc` provides `JdbcRuntimeStore` (a `DataSource` is injected; no connection pool
is bundled) and `HelmSchema.migrate(dataSource)` which runs the Flyway migrations from
`db/migration/V1__init.sql`. Callers may instead self-manage migrations.

```java
DataSource dataSource = /* your DataSource */;
HelmSchema.migrate(dataSource);
RuntimeStore store = new JdbcRuntimeStore(dataSource);
AgentRuntime runtime = AgentRuntime.builder().agent(agent).provider(provider).store(store).build();
```

Payloads (messages, inputs, outputs, errors, event payloads) are stored as JSON text columns;
timestamps as `TIMESTAMP`; statuses as the enum name. Each write is its own transaction; SQL errors
map to `PersistenceException`.

The same `RuntimeStoreContractTest` that the in-memory store passes also runs against the JDBC store
on H2, and a file-mode H2 test verifies records survive a reconnect (simulated restart).

## Observability

`helm-observability-logging` provides `LoggingRuntimeObserver`, a `RuntimeEventObserver`
(defined in `helm-core`) that emits structured, parameterized SLF4J log lines: lifecycle events at
INFO, model/tool/skill activity at DEBUG, errors at WARN. The observer logs only metadata
(type, ids, sequence) and never the event payload, so it cannot leak credentials; it relies on the
runtime's prior redaction.

```java
RuntimeEventObserver observer = new LoggingRuntimeObserver();
// wire the observer to the runtime's event stream (e.g. via an observing store decorator)
```

## Verification

- `RuntimeStoreContractTest` passes on both `InMemoryRuntimeStore` and `JdbcRuntimeStore` (H2).
- `JdbcRuntimeStoreRestartTest` reconnects to a file-mode H2 database and verifies persisted
  operations are recoverable.
- `LoggingRuntimeObserverTest` verifies log levels and that event payloads are never logged.
- `mvn verify` is green (170 tests). No external database service is required (H2 only).
