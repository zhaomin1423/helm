package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.EmbeddingProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic embedding provider for tests and local development. Hashes token sets into a fixed-dimensional vector
 * so that semantically related text (sharing tokens or synonyms from a small built-in map) produces nearby vectors.
 * Never touches the network.
 *
 * <p>The synonym map is intentionally small and tuned for the contract tests (fast/express, delivery/shipping,
 * billing/invoice). Real semantic quality is the job of provider adapters; this fake only validates the SPI wiring
 * end-to-end.
 *
 * @since 0.2.0
 */
public final class FakeEmbeddingProvider implements EmbeddingProvider {

    /** Fixed dimensionality of vectors produced by this provider. */
    public static final int DIMENSION = 64;

    @Override
    public float[] embed(String text) {
        Set<String> tokens = tokenize(text);
        float[] vector = new float[DIMENSION];
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), DIMENSION);
            vector[bucket] += 1.0f;
            for (String synonym : synonyms(token)) {
                int synBucket = Math.floorMod(synonym.hashCode(), DIMENSION);
                vector[synBucket] += 0.5f;
            }
        }
        return normalize(vector);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("\\W+")));
    }

    private static String[] synonyms(String token) {
        return switch (token) {
            case "fast", "quick", "speed", "rapid" -> new String[] {"express"};
            case "express" -> new String[] {"fast"};
            case "delivery" -> new String[] {"shipping"};
            case "shipping" -> new String[] {"delivery"};
            case "preference", "prefers", "prefer" -> new String[] {"prefers"};
            case "invoice" -> new String[] {"billing"};
            case "billing" -> new String[] {"invoice"};
            default -> new String[0];
        };
    }

    private static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
