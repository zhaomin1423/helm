package io.agent.helm.core.error;

import java.util.Map;

public final class ContextOverflowException extends HelmException {
    public ContextOverflowException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("CONTEXT_OVERFLOW", message, details, developerDetails);
    }
}
