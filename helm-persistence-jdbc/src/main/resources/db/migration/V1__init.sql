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
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_helm_event_operation ON helm_event (operation_id, sequence);
CREATE INDEX idx_helm_event_run ON helm_event (workflow_run_id, sequence);
