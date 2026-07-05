package io.agent.helm.memory.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FakeEmbeddingProviderTest {

    @Test
    void dimensionIs64() {
        assertThat(new FakeEmbeddingProvider().dimension()).isEqualTo(64);
    }

    @Test
    void embedReturnsVectorOfDeclaredDimension() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        assertThat(provider.embed("anything")).hasSize(provider.dimension());
    }

    @Test
    void embedIsDeterministicForSameInput() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        float[] a = provider.embed("fast delivery");
        float[] b = provider.embed("fast delivery");
        assertThat(Arrays.equals(a, b)).isTrue();
    }

    @Test
    void embedProducesNonZeroVector() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        float[] vector = provider.embed("hello world");
        double norm = 0.0;
        for (float f : vector) {
            norm += f * f;
        }
        assertThat(Math.sqrt(norm)).isGreaterThan(0.0);
    }

    @Test
    void differentInputsProduceDifferentVectors() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        float[] a = provider.embed("fast delivery");
        float[] b = provider.embed("invoice billing");
        assertThat(Arrays.equals(a, b)).isFalse();
    }

    @Test
    void synonymTokensProduceNearbyVectors() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        float[] fast = provider.embed("fast");
        float[] express = provider.embed("express");
        float[] java = provider.embed("java");
        // "fast" and "express" are linked as synonyms, so their vectors should be more similar
        // to each other than to an unrelated token.
        double fastExpress = CosineSimilarity.cosine(fast, express);
        double fastJava = CosineSimilarity.cosine(fast, java);
        assertThat(fastExpress).isGreaterThan(fastJava);
    }

    @Test
    void handlesEmptyInputGracefully() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        float[] vector = provider.embed("");
        assertThat(vector).hasSize(64);
        // Zero vector is a valid (if unhelpful) result.
        double norm = 0.0;
        for (float f : vector) {
            norm += f * f;
        }
        assertThat(Math.sqrt(norm)).isEqualTo(0.0);
    }
}
