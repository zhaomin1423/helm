package io.agent.helm.core.admission;

/** Result of {@link RateLimiter#tryAcquire}. {@code retryAfterMs} is a hint for HTTP {@code Retry-After}. */
public record AcquisitionResult(boolean allowed, long retryAfterMs, long remaining) {
    public static AcquisitionResult allow() {
        return new AcquisitionResult(true, 0, -1);
    }

    public static AcquisitionResult denied(long retryAfterMs) {
        return new AcquisitionResult(false, Math.max(0, retryAfterMs), 0);
    }
}
