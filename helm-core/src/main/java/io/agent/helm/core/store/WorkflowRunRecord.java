package io.agent.helm.core.store;

import io.agent.helm.core.error.HelmException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Persisted record of a workflow run. {@code input}/{@code output} are raw JSON strings — callers MUST pass
 * JSON-serializable strings (the record does not validate or parse them).
 */
public record WorkflowRunRecord(
        String id,
        String workflowName,
        WorkflowRunStatus status,
        String input,
        String output,
        Map<String, Object> error,
        Instant createdAt,
        Instant completedAt) {
    public WorkflowRunRecord {
        Objects.requireNonNull(id, "id");
        status = Objects.requireNonNull(status, "status");
        error = HelmException.copySafe(error);
    }
}
