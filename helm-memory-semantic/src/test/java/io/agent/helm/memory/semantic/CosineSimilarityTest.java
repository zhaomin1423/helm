package io.agent.helm.memory.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CosineSimilarityTest {

    @Test
    void identicalVectorsHaveSimilarityOne() {
        float[] v = {1.0f, 2.0f, 3.0f};
        assertThat(CosineSimilarity.cosine(v, v)).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void orthogonalVectorsHaveSimilarityZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertThat(CosineSimilarity.cosine(a, b)).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void oppositeVectorsHaveSimilarityMinusOne() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertThat(CosineSimilarity.cosine(a, b)).isEqualTo(-1.0, within(1e-9));
    }

    @Test
    void zeroVectorReturnsZero() {
        float[] zero = {0.0f, 0.0f};
        float[] other = {1.0f, 2.0f};
        assertThat(CosineSimilarity.cosine(zero, other)).isEqualTo(0.0, within(1e-9));
        assertThat(CosineSimilarity.cosine(other, zero)).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void nullReturnsZero() {
        assertThat(CosineSimilarity.cosine(null, new float[] {1.0f})).isEqualTo(0.0);
        assertThat(CosineSimilarity.cosine(new float[] {1.0f}, null)).isEqualTo(0.0);
    }

    @Test
    void emptyReturnsZero() {
        assertThat(CosineSimilarity.cosine(new float[0], new float[] {1.0f})).isEqualTo(0.0);
        assertThat(CosineSimilarity.cosine(new float[0], new float[0])).isEqualTo(0.0);
    }

    @Test
    void matchesCosineOfAngle() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 1.0f}; // 45 degrees from a
        // cos(45) = 1 / sqrt(2) ≈ 0.7071
        assertThat(CosineSimilarity.cosine(a, b)).isEqualTo(Math.cos(Math.PI / 4), within(1e-9));
    }

    @Test
    void mismatchedLengthsCompareOverCommonPrefix() {
        float[] a = {1.0f, 0.0f, 99.0f}; // extra component ignored
        float[] b = {1.0f, 0.0f};
        assertThat(CosineSimilarity.cosine(a, b)).isEqualTo(1.0, within(1e-9));
    }
}
