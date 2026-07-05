package io.agent.helm.core.admission;

import io.agent.helm.core.annotation.Experimental;

/**
 * Application-supplied rate limiter, called at admission (before an operation/run record is created). Token-bucket is
 * the default in-memory implementation; adapters may back this with Redis or a shared store. {@code @Experimental} the
 * SPI shape is being validated.
 */
@Experimental
public interface RateLimiter {
    AcquisitionResult tryAcquire(RateLimitKey key);

    /**
     * Releases a previously acquired permit back to the limiter. Implementations that do not support refund may treat
     * this as a no-op; callers must not assume the permit is reusable.
     */
    default void release(RateLimitKey key) {}

    /** No-op limiter for local/dev use. */
    static RateLimiter unlimited() {
        return key -> AcquisitionResult.allow();
    }
}
