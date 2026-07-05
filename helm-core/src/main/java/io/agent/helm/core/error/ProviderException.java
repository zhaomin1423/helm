package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when a model provider call fails (HTTP error, rate limit, timeout, or protocol failure). Stable codes
 * distinguish the common failure kinds; specifics go in {@code details}.
 */
public final class ProviderException extends HelmException {
    /** Stable code string for generic provider errors; mirrors {@link ErrorCode#PROVIDER_ERROR}. */
    public static final String CODE = ErrorCode.PROVIDER_ERROR.stable();

    /** Stable code string for provider rate-limited errors; mirrors {@link ErrorCode#PROVIDER_RATE_LIMITED}. */
    public static final String RATE_LIMITED = ErrorCode.PROVIDER_RATE_LIMITED.stable();

    /** Stable code string for provider timeout errors; mirrors {@link ErrorCode#PROVIDER_TIMEOUT}. */
    public static final String TIMEOUT = ErrorCode.PROVIDER_TIMEOUT.stable();

    public ProviderException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }

    public ProviderException(
            ErrorCode code,
            String message,
            Map<String, Object> details,
            Map<String, Object> developerDetails,
            Throwable cause) {
        super(code, message, details, developerDetails, cause);
    }

    public ProviderException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        this(ErrorCode.PROVIDER_ERROR, message, details, developerDetails);
    }

    public ProviderException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        this(ErrorCode.PROVIDER_ERROR, message, details, developerDetails, cause);
    }
}
