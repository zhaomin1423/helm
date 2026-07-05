package io.agent.helm.core.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * A single long-term memory entry. Memories are scoped by {@code scopeId} (typically {@code agentName:instanceId}) so
 * that they survive across sessions of the same agent instance.
 */
public record MemoryRecord(String id, String scopeId, String subject, String content, Instant createdAt) {
    public MemoryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scopeId, "scopeId");
        subject = subject == null ? "" : subject;
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
