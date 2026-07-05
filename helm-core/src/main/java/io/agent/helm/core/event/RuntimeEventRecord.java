package io.agent.helm.core.event;

import io.agent.helm.core.error.HelmException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A single runtime event persisted to {@link io.agent.helm.core.store.EventStore}. The {@code type} is a stable
 * {@link RuntimeEventType} enum value; the {@code payload} is a safe-to-surface structured map.
 */
public record RuntimeEventRecord(
        String id,
        String operationId,
        String workflowRunId,
        long sequence,
        RuntimeEventType type,
        Map<String, Object> payload,
        Instant createdAt) {
    public RuntimeEventRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        payload = HelmException.copySafe(payload);
    }
}
