package io.agent.helm.core.error;

import java.util.Map;

public final class SessionBusyException extends HelmException {
    public SessionBusyException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("SESSION_BUSY", message, details, developerDetails);
    }
}
