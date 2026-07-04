package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when a model provider call fails (HTTP error, rate limit, timeout, or protocol failure). Stable codes
 * distinguish the common failure kinds; specifics go in {@code details}.
 */
public final class ProviderException extends HelmException {
    public static final String CODE = "PROVIDER_ERROR";
    public static final String RATE_LIMITED = "PROVIDER_RATE_LIMITED";
    public static final String TIMEOUT = "PROVIDER_TIMEOUT";

    public ProviderException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }

    public ProviderException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        this(CODE, message, details, developerDetails);
    }
}
