package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when a request lacks authentication ({@code UNAUTHORIZED}) or the caller lacks permission ({@code FORBIDDEN}).
 */
public final class AuthorizationException extends HelmException {
    public AuthorizationException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }

    public static AuthorizationException unauthorized(String message, Map<String, Object> details) {
        return new AuthorizationException(ErrorCode.UNAUTHORIZED, message, details, Map.of());
    }

    public static AuthorizationException forbidden(String message, Map<String, Object> details) {
        return new AuthorizationException(ErrorCode.FORBIDDEN, message, details, Map.of());
    }
}
