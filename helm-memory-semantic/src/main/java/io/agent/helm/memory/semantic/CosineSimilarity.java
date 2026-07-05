package io.agent.helm.memory.semantic;

/**
 * Cosine similarity utility for float vectors used by in-memory semantic retrieval.
 *
 * @since 0.2.0
 */
public final class CosineSimilarity {
    private CosineSimilarity() {}

    /**
     * Cosine similarity between two vectors. Returns {@code 0.0} when either vector is {@code null}, empty, or
     * zero-magnitude (avoiding division by zero). Vectors of differing length compare only over their common prefix; in
     * practice both come from the same {@link io.agent.helm.core.memory.EmbeddingProvider} and share its
     * dimensionality.
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null) {
            return 0.0;
        }
        int len = Math.min(a.length, b.length);
        if (len == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}
