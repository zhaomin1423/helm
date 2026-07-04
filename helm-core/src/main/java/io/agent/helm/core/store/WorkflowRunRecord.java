package io.agent.helm.core.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record WorkflowRunRecord(
        String id,
        String workflowName,
        WorkflowRunStatus status,
        Object input,
        Object output,
        Map<String, Object> error,
        Instant createdAt,
        Instant completedAt) {
    public WorkflowRunRecord {
        status = Objects.requireNonNull(status, "status");
        error = Map.copyOf(Objects.requireNonNull(error, "error"));
    }
}
