package io.agent.helm.core.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record OperationRecord(
        String id,
        String sessionId,
        String type,
        OperationStatus status,
        Object input,
        Object output,
        Map<String, Object> error,
        Instant createdAt,
        Instant completedAt) {
    public OperationRecord {
        status = Objects.requireNonNull(status, "status");
        error = Map.copyOf(Objects.requireNonNull(error, "error"));
    }
}
