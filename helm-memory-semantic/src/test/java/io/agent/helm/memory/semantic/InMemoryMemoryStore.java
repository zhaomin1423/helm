package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory {@link MemoryStore} for {@code helm-memory-semantic} tests. Mirrors the behavior of
 * {@code io.agent.helm.runtime.memory.InMemoryMemoryStore} so the semantic module's tests stay self-contained and
 * depend only on {@code helm-core}, per the component design (section 7: "helm-memory-semantic 模块独立编译，依赖仅 helm-core").
 * Keyword {@code search} is case-insensitive substring match; {@code list} is sorted by {@code createdAt} ascending —
 * matching the {@link MemoryStore} contract.
 */
final class InMemoryMemoryStore implements MemoryStore {

    private final ConcurrentMap<String, MemoryRecord> memories = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord memory) {
        memories.put(memory.id(), memory);
    }

    @Override
    public Optional<MemoryRecord> load(String memoryId) {
        return Optional.ofNullable(memories.get(memoryId));
    }

    @Override
    public List<MemoryRecord> list(String scopeId) {
        return memories.values().stream()
                .filter(memory -> memory.scopeId().equals(scopeId))
                .sorted(Comparator.comparing(MemoryRecord::createdAt))
                .toList();
    }

    @Override
    public List<MemoryRecord> search(String scopeId, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return list(scopeId).stream()
                .filter(memory -> memory.subject().toLowerCase(Locale.ROOT).contains(needle)
                        || memory.content().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    @Override
    public void delete(String memoryId) {
        memories.remove(memoryId);
    }
}
