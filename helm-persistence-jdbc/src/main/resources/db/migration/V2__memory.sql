CREATE TABLE helm_memory (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    scope_id        VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    content         CLOB NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_helm_memory_scope ON helm_memory (scope_id, created_at);
