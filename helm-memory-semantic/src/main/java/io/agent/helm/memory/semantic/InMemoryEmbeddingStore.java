package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.EmbeddingStore;
import io.agent.helm.core.memory.MemoryRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link EmbeddingStore} for development and tests. Stores vectors and records in {@link ConcurrentHashMap}s
 * and performs a linear scan with cosine similarity for {@link #search}.
 *
 * <p>The {@link EmbeddingStore} SPI's {@code store} method only receives a vector (not the full {@link MemoryRecord}),
 * yet {@link EmbeddingStore#search} must return full records. This implementation therefore exposes an additional
 * {@link #index(MemoryRecord)} method: {@link SemanticMemoryStore} calls {@code index} after persisting the record so
 * that {@link #search} can return it. Entries with zero cosine similarity to the query are excluded, so searches only
 * return positively related memories.
 *
 * @since 0.2.0
 */
public final class InMemoryEmbeddingStore implements EmbeddingStore {

    private record Entry(String scopeId, float[] vector) {}

    private final ConcurrentMap<String, Entry> vectors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MemoryRecord> records = new ConcurrentHashMap<>();

    /**
     * Indexes a memory record so {@link #search} can return it. Replaces any prior record for the same id. This is a
     * non-SPI extension; callers using only the {@link EmbeddingStore} interface do not need to invoke it directly.
     */
    public void index(MemoryRecord memory) {
        Objects.requireNonNull(memory, "memory");
        records.put(memory.id(), memory);
    }

    @Override
    public void store(String memoryId, String scopeId, float[] vector) {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(vector, "vector");
        vectors.put(memoryId, new Entry(scopeId, vector.clone()));
    }

    @Override
    public List<MemoryRecord> search(String scopeId, float[] queryVector, int topK) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(queryVector, "queryVector");
        if (topK <= 0) {
            return List.of();
        }
        record Score(String memoryId, double similarity) {}
        List<Score> scored = new ArrayList<>();
        for (Map.Entry<String, Entry> e : vectors.entrySet()) {
            if (e.getValue().scopeId().equals(scopeId)) {
                scored.add(new Score(
                        e.getKey(), CosineSimilarity.cosine(e.getValue().vector(), queryVector)));
            }
        }
        scored.sort(Comparator.comparingDouble(Score::similarity).reversed());
        List<MemoryRecord> result = new ArrayList<>(Math.min(topK, scored.size()));
        for (Score s : scored) {
            if (s.similarity() <= 0.0) {
                continue;
            }
            if (result.size() >= topK) {
                break;
            }
            MemoryRecord record = records.get(s.memoryId());
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    @Override
    public void delete(String memoryId) {
        Objects.requireNonNull(memoryId, "memoryId");
        vectors.remove(memoryId);
        records.remove(memoryId);
    }

    /** Number of indexed vectors. For tests and diagnostics. */
    int size() {
        return vectors.size();
    }
}
