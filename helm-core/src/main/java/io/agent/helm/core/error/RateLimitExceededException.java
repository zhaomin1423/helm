package io.agent.helm.core.error;

import java.util.Map;

/** Raised at admission when a rate limit is exceeded. {@code retryAfterMs} goes in {@code details}. */
public final class RateLimitExceededException extends HelmException {
    public RateLimitExceededException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.RATE_LIMITED, message, details, developerDetails);
    }

    public RateLimitExceededException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.RATE_LIMITED, message, details, developerDetails, cause);
    }
}
