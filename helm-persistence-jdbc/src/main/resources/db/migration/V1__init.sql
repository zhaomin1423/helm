-- Helm runtime schema. Target database: H2 (embedded / file). Production DB support is currently H2-only;
-- the MERGE ... KEY syntax and CLOB column types used here are H2-specific. Adding another vendor requires
-- per-vendor SQL (e.g. INSERT ... ON CONFLICT for PostgreSQL) in a separate migration.
--
-- Referential FKs from helm_operation.session_id / helm_event.operation_id / helm_event.workflow_run_id to their
-- parents are deliberately NOT declared here: the RuntimeStore contract (see helm-core RuntimeStoreContractTest)
-- treats operations and events as standalone — it persists an operation or event without first creating the parent
-- session/operation. Enforced FKs would reject those writes. Referential integrity and cascade-on-delete are instead
-- enforced at the adapter layer (JdbcRuntimeStore.deleteSession cascades to operations and events in one transaction).

CREATE TABLE helm_session (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    agent_name      VARCHAR(255) NOT NULL,
    instance_id     VARCHAR(255) NOT NULL,
    session_name    VARCHAR(255) NOT NULL,
    version         BIGINT NOT NULL,
    messages        CLOB NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE TABLE helm_operation (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    session_id      VARCHAR(255) NOT NULL,
    type            VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    input           CLOB,
    output          CLOB,
    error           CLOB,
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP
);

CREATE INDEX idx_helm_operation_created ON helm_operation (created_at);
CREATE INDEX idx_helm_operation_session ON helm_operation (session_id);

CREATE TABLE helm_workflow_run (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    workflow_name   VARCHAR(255) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    input           CLOB,
    output          CLOB,
    error           CLOB,
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP
);

CREATE INDEX idx_helm_workflow_run_created ON helm_workflow_run (created_at);
CREATE INDEX idx_helm_workflow_run_name ON helm_workflow_run (workflow_name);

CREATE TABLE helm_event (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    operation_id    VARCHAR(255),
    workflow_run_id VARCHAR(255),
    sequence        BIGINT NOT NULL,
    type            VARCHAR(128) NOT NULL,
    payload         CLOB,
    created_at      TIMESTAMP NOT NULL,
    -- At-least-once retry safety: a replayed event with the same (operation_id, sequence) or
    -- (workflow_run_id, sequence) is a no-op (caught as a unique violation by the adapter). NULL operation_id /
    -- workflow_run_id do not participate in the constraint (standard SQL NULL semantics), so workflow events
    -- (operation_id NULL) and operation events (workflow_run_id NULL) are governed by their respective constraint.
    CONSTRAINT uk_helm_event_operation_seq UNIQUE (operation_id, sequence),
    CONSTRAINT uk_helm_event_run_seq UNIQUE (workflow_run_id, sequence)
);

CREATE INDEX idx_helm_event_operation ON helm_event (operation_id, sequence);
CREATE INDEX idx_helm_event_run ON helm_event (workflow_run_id, sequence);
