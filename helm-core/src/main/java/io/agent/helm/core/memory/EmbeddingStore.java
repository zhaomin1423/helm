package io.agent.helm.core.memory;

import io.agent.helm.core.annotation.Experimental;
import java.util.List;

/**
 * Vector store for semantic memory search. {@link #store} persists a vector alongside a memory; {@link #search} returns
 * the top-K most similar memories by cosine similarity. @Experimental semantic retrieval SPI shape is being validated.
 */
@Experimental
public interface EmbeddingStore {
    void store(String memoryId, String scopeId, float[] vector);

    List<MemoryRecord> search(String scopeId, float[] queryVector, int topK);

    void delete(String memoryId);
}
