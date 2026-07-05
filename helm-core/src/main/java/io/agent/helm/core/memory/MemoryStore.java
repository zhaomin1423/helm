package io.agent.helm.core.memory;

import java.util.List;
import java.util.Optional;

/**
 * SPI for long-term agent memory. Implementations persist {@link MemoryRecord} entries across sessions of the same
 * scope. In-memory and JDBC-backed implementations must satisfy the shared contract tests.
 */
public interface MemoryStore {
    void save(MemoryRecord memory);

    Optional<MemoryRecord> load(String memoryId);

    /** Lists memories for a scope, ordered by {@code createdAt} ascending. */
    List<MemoryRecord> list(String scopeId);

    /**
     * Returns memories in the scope whose subject or content contains {@code query} (case-insensitive), ordered by
     * {@code createdAt} ascending.
     */
    List<MemoryRecord> search(String scopeId, String query);

    void delete(String memoryId);
}
