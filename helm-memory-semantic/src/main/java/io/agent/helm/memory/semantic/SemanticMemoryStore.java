package io.agent.helm.memory.semantic;

import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.core.memory.EmbeddingProvider;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorates a {@link MemoryStore} with semantic (vector) retrieval. {@code save} persists the record via the delegate
 * and additionally embeds the content and indexes the vector. {@code search} embeds the query and delegates to
 * {@link IndexedEmbeddingStore}; it falls back to keyword search via the delegate when no embedding provider is wired,
 * when embedding fails, or when no vectors are indexed for the scope.
 *
 * <p>This is a decorator: {@link #load}, {@link #list}, and {@link #delete} delegate directly. The {@link MemoryStore}
 * contract is preserved, so a {@code SemanticMemoryStore} can replace any {@code MemoryStore} without breaking existing
 * consumers.
 *
 * <p>The vector store is required to be an {@link IndexedEmbeddingStore}: the core {@link EmbeddingStore} SPI's
 * {@code store} method accepts only a vector, not the {@link MemoryRecord} payload needed to reconstruct search
 * results, so {@code save} hands the record to {@link IndexedEmbeddingStore#index} after {@link EmbeddingStore#store}
 * succeeds. This decouples the decorator from any concrete {@link EmbeddingStore} — any pgvector/Weaviate adapter that
 * implements {@link IndexedEmbeddingStore} works without a special-case {@code instanceof}.
 *
 * @since 0.2.0
 */
public final class SemanticMemoryStore implements MemoryStore {

    private final MemoryStore delegate;
    private final EmbeddingProvider embeddingProvider;
    private final IndexedEmbeddingStore embeddingStore;
    private final int topK;

    /**
     * @param delegate the underlying store that persists records.
     * @param embeddingProvider text-to-vector provider; {@code null} disables semantic search.
     * @param embeddingStore vector store; {@code null} disables semantic search. Must be {@code null} when
     *     {@code embeddingProvider} is {@code null}, and non-{@code null} otherwise. When non-{@code null} it must be
     *     an {@link IndexedEmbeddingStore} so {@code save} can hand off the {@link MemoryRecord} payload for
     *     reconstruction by {@code search}.
     * @param topK maximum results returned by semantic search; must be positive.
     */
    public SemanticMemoryStore(
            MemoryStore delegate, EmbeddingProvider embeddingProvider, IndexedEmbeddingStore embeddingStore, int topK) {
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
        float[] vector;
        try {
            String text = memory.subject().isBlank() ? memory.content() : memory.subject() + " " + memory.content();
            vector = embeddingProvider.embed(text);
        } catch (RuntimeException e) {
            // Embedding failure must not break save: the record is already persisted via the delegate, and search
            // falls back to keyword when no vector is available. See design doc section 5.5 (double-write
            // consistency trade-off).
            return;
        }
        int expected = embeddingProvider.dimension();
        if (vector.length != expected) {
            throw new IllegalStateException("embedding dimension mismatch: provider.dimension()=" + expected
                    + " but embed returned length=" + vector.length + " for memory " + memory.id());
        }
        try {
            embeddingStore.store(memory.id(), memory.scopeId(), vector);
        } catch (RuntimeException e) {
            // Surface embedding-store failures: a silently dropped store call would leave the record unsearchable
            // with no signal to the caller, and historically also leaked a half-indexed entry. Index only after
            // store succeeds, so a failed store never leaves a record behind.
            throw new PersistenceException(
                    "failed to index memory " + memory.id() + " in embedding store: " + e.getMessage(), null, null, e);
        }
        embeddingStore.index(memory);
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
