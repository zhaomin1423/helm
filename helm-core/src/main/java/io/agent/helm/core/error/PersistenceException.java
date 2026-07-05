package io.agent.helm.core.error;

import java.util.Map;

public final class PersistenceException extends HelmException {
    public PersistenceException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.PERSISTENCE_ERROR, message, details, developerDetails);
    }

    public PersistenceException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.PERSISTENCE_ERROR, message, details, developerDetails, cause);
    }
}
