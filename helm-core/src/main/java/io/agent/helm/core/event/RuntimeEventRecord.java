package io.agent.helm.core.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RuntimeEventRecord(
        String id,
        String operationId,
        String workflowRunId,
        long sequence,
        String type,
        Map<String, Object> payload,
        Instant createdAt) {
    public RuntimeEventRecord {
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
    }
}
