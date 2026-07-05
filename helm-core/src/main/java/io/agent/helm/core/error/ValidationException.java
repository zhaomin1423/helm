package io.agent.helm.core.error;

import java.util.Map;

public final class ValidationException extends HelmException {
    public ValidationException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.VALIDATION_FAILED, message, details, developerDetails);
    }

    public ValidationException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.VALIDATION_FAILED, message, details, developerDetails, cause);
    }
}
