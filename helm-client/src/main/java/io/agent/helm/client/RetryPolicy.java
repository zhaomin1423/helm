package io.agent.helm.client;

import java.time.Duration;

/**
 * Retry policy for {@link JdkHttpTransport}. Retries are attempted only on idempotent methods (GET and HEAD);
 * non-idempotent methods (POST, PUT, DELETE) are never retried. {@code 429} honors {@code Retry-After};
 * {@code 408}/{@code 503}/{@code 504} and {@link java.io.IOException} are retried with exponential backoff.
 *
 * @param maxAttempts maximum number of attempts (including the first; e.g. 3 = 1 initial + 2 retries).
 * @param initialBackoff base backoff for the first retry.
 * @param maxBackoff upper bound on a single backoff.
 * @param retryAfterCap upper bound applied when sleeping per {@code Retry-After} header.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration retryAfterCap) {

    public static final RetryPolicy DISABLED = new RetryPolicy(1, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    public static final RetryPolicy DEFAULT =
            new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(60));

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ZERO : maxBackoff;
        retryAfterCap = retryAfterCap == null ? Duration.ZERO : retryAfterCap;
    }
}
