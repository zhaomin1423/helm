package io.agent.helm.core.error;

import java.util.Map;

/** The engine was interrupted (thread interrupted) while waiting for a model turn to complete. */
public final class EngineInterruptedException extends EngineException {
    public EngineInterruptedException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.ENGINE_INTERRUPTED, message, details, developerDetails);
    }

    public EngineInterruptedException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.ENGINE_INTERRUPTED, message, details, developerDetails, cause);
    }
}
