package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.EmbeddingStore;
import io.agent.helm.core.memory.MemoryRecord;

/**
 * Extends {@link EmbeddingStore} with the ability to index the full {@link MemoryRecord} payload so that
 * {@link #search} can reconstruct records for its results. The base {@link EmbeddingStore#store} SPI accepts only a
 * vector (not the payload), yet {@link EmbeddingStore#search} must return full records; implementations that back
 * {@link SemanticMemoryStore} must therefore implement this extended interface so {@code save} can hand the record off
 * for later reconstruction.
 *
 * <p>Defining this interface in {@code helm-memory-semantic} (rather than {@code helm-core}) keeps the payload-indexing
 * concern local to the decorator that requires it: {@link SemanticMemoryStore} {@code save} requires an
 * {@code IndexedEmbeddingStore} and calls {@link #index} after {@link EmbeddingStore#store} succeeds, decoupling the
 * decorator from any concrete {@link EmbeddingStore} such as {@link InMemoryEmbeddingStore}. A real pgvector or
 * Weaviate adapter implements this interface to bridge the SPI gap.
 *
 * @since 0.2.0
 */
public interface IndexedEmbeddingStore extends EmbeddingStore {

    /**
     * Index the full memory record so {@link #search} can return it. Replaces any prior record for the same id. Called
     * by {@link SemanticMemoryStore#save} only after {@link EmbeddingStore#store} has succeeded, so a failing
     * {@code store} never leaves a half-indexed record behind.
     *
     * @param memory the memory record to index; never {@code null}.
     */
    void index(MemoryRecord memory);
}
