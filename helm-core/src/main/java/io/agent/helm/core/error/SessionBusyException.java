package io.agent.helm.core.error;

import java.util.Map;

public final class SessionBusyException extends HelmException {
    public SessionBusyException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.SESSION_BUSY, message, details, developerDetails);
    }

    public SessionBusyException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.SESSION_BUSY, message, details, developerDetails, cause);
    }
}
