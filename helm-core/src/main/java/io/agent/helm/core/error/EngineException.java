package io.agent.helm.core.error;

import java.util.Map;

/**
 * Base class for engine execution failures. Subclasses carry a stable {@link ErrorCode} so callers, HTTP adapters, and
 * event consumers can distinguish timeout, max-turn, stream failure, and interruption.
 */
public abstract class EngineException extends HelmException {

    protected EngineException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }

    protected EngineException(
            ErrorCode code,
            String message,
            Map<String, Object> details,
            Map<String, Object> developerDetails,
            Throwable cause) {
        super(code, message, details, developerDetails, cause);
    }
}
