package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.EmbeddingProvider;
import io.agent.helm.core.memory.EmbeddingStore;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorates a {@link MemoryStore} with semantic (vector) retrieval. {@code save} persists the record via the delegate
 * and additionally embeds the content and indexes the vector. {@code search} embeds the query and delegates to
 * {@link EmbeddingStore}; it falls back to keyword search via the delegate when no embedding provider is wired, when
 * embedding fails, or when no vectors are indexed for the scope.
 *
 * <p>This is a decorator: {@link #load}, {@link #list}, and {@link #delete} delegate directly. The {@link MemoryStore}
 * contract is preserved, so a {@code SemanticMemoryStore} can replace any {@code MemoryStore} without breaking existing
 * consumers.
 *
 * @since 0.2.0
 */
public final class SemanticMemoryStore implements MemoryStore {

    private final MemoryStore delegate;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;
    private final int topK;

    /**
     * @param delegate the underlying store that persists records.
     * @param embeddingProvider text-to-vector provider; {@code null} disables semantic search.
     * @param embeddingStore vector store; {@code null} disables semantic search. Must be {@code null} when
     *     {@code embeddingProvider} is {@code null}, and non-{@code null} otherwise.
     * @param topK maximum results returned by semantic search; must be positive.
     */
    public SemanticMemoryStore(
            MemoryStore delegate, EmbeddingProvider embeddingProvider, EmbeddingStore embeddingStore, int topK) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        this.topK = topK;
        if ((embeddingProvider == null) != (embeddingStore == null)) {
            throw new IllegalArgumentException(
                    "embeddingProvider and embeddingStore must be both null or both non-null");
        }
        this.embeddingProvider = embeddingProvider;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public void save(MemoryRecord memory) {
        delegate.save(memory);
        if (embeddingProvider == null || embeddingStore == null) {
            return;
        }
        try {
            String text = memory.subject().isBlank() ? memory.content() : memory.subject() + " " + memory.content();
            float[] vector = embeddingProvider.embed(text);
            // Index the record first so InMemoryEmbeddingStore.search can return it; the SPI's store
            // method only accepts a vector, so the record is supplied via this extra step.
            if (embeddingStore instanceof InMemoryEmbeddingStore indexed) {
                indexed.index(memory);
            }
            embeddingStore.store(memory.id(), memory.scopeId(), vector);
        } catch (RuntimeException e) {
            // Embedding or vector-store failure must not break save: the record is already
            // persisted via the delegate, and search falls back to keyword when no vector
            // is available. See design doc section 5.5 (double-write consistency trade-off).
        }
    }

    @Override
    public Optional<MemoryRecord> load(String memoryId) {
        return delegate.load(memoryId);
    }

    @Override
    public List<MemoryRecord> list(String scopeId) {
        return delegate.list(scopeId);
    }

    @Override
    public List<MemoryRecord> search(String scopeId, String query) {
        if (embeddingProvider == null || embeddingStore == null) {
            return delegate.search(scopeId, query);
        }
        try {
            float[] queryVector = embeddingProvider.embed(query);
            List<MemoryRecord> semantic = embeddingStore.search(scopeId, queryVector, topK);
            if (semantic.isEmpty()) {
                // No vectors indexed for this scope (e.g. pre-existing memories); fall back.
                return delegate.search(scopeId, query);
            }
            return semantic;
        } catch (RuntimeException e) {
            // Embedding failure must not break prompt; fall back to keyword search.
            return delegate.search(scopeId, query);
        }
    }

    @Override
    public void delete(String memoryId) {
        delegate.delete(memoryId);
        if (embeddingStore != null) {
            embeddingStore.delete(memoryId);
        }
    }
}
