package io.agent.helm.core.admission;

import java.util.OptionalLong;

/**
 * Result of {@link RateLimiter#tryAcquire}. {@code retryAfterMs} is a hint for HTTP {@code Retry-After}.
 * {@code remaining} uses {@link OptionalLong#empty()} to signal "unknown / not enforced" and
 * {@link OptionalLong#of(long)} to report a concrete remaining quota; callers must not rely on a sentinel value.
 */
public record AcquisitionResult(boolean allowed, long retryAfterMs, OptionalLong remaining) {
    /** Allow the call; remaining quota is unknown. */
    public static AcquisitionResult allow() {
        return new AcquisitionResult(true, 0L, OptionalLong.empty());
    }

    /** Allow the call and report {@code remaining} quota left after this acquisition. */
    public static AcquisitionResult allow(long remaining) {
        return new AcquisitionResult(true, 0L, OptionalLong.of(remaining));
    }

    /** Deny the call; {@code retryAfterMs} is clamped to {@code >= 0}. */
    public static AcquisitionResult denied(long retryAfterMs) {
        return new AcquisitionResult(false, Math.max(0, retryAfterMs), OptionalLong.of(0));
    }
}
