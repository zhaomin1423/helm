package io.agent.helm.core.admission;

import java.util.Objects;

/**
 * Key for a rate-limit dimension: per-principal, per-agent, per-session, or global. Callers must pass an explicit
 * dimension; use {@link #global()} when a global limiter is intended.
 *
 * @param dimension a non-null, non-blank dimension identifier (e.g. {@link #PRINCIPAL}, {@link #AGENT},
 *     {@link #SESSION}, {@link #GLOBAL}).
 * @param value the dimension value (e.g. the principal id); may be empty for the global dimension.
 */
public record RateLimitKey(String dimension, String value) {
    public static final String PRINCIPAL = "PRINCIPAL";
    public static final String AGENT = "AGENT";
    public static final String SESSION = "SESSION";
    public static final String GLOBAL = "GLOBAL";

    public RateLimitKey {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        value = value == null ? "" : value;
    }

    public static RateLimitKey principal(String value) {
        return new RateLimitKey(PRINCIPAL, value);
    }

    public static RateLimitKey agent(String value) {
        return new RateLimitKey(AGENT, value);
    }

    public static RateLimitKey global() {
        return new RateLimitKey(GLOBAL, "");
    }
}
