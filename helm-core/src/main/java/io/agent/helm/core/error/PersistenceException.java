package io.agent.helm.core.error;

import java.util.Map;

public final class PersistenceException extends HelmException {
    public PersistenceException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("PERSISTENCE_ERROR", message, details, developerDetails);
    }
}
