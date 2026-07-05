package io.agent.helm.core.store;

import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.security.HelmAction;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Persisted record of an agent operation. {@code type} is a stable {@link HelmAction} enum value identifying the entry
 * point that drove the operation. {@code input}/{@code output} are raw JSON strings — callers MUST pass
 * JSON-serializable strings (the record does not validate or parse them).
 */
public record OperationRecord(
        String id,
        String sessionId,
        HelmAction type,
        OperationStatus status,
        String input,
        String output,
        Map<String, Object> error,
        Instant createdAt,
        Instant completedAt) {
    public OperationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        status = Objects.requireNonNull(status, "status");
        error = HelmException.copySafe(error);
    }
}
