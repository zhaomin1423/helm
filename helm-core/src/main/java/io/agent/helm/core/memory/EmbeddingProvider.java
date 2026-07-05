package io.agent.helm.core.memory;

import io.agent.helm.core.annotation.Experimental;

/**
 * Computes a vector embedding for text. Implemented by provider adapters (e.g. OpenAI embeddings) or local
 * models. @Experimental semantic retrieval SPI shape is being validated.
 */
@Experimental
public interface EmbeddingProvider {
    float[] embed(String text);

    int dimension();
}
