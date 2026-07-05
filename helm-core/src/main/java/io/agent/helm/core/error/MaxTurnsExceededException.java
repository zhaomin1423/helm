package io.agent.helm.core.error;

import java.util.Map;

/** The agent loop exceeded the configured maximum number of turns without reaching a terminal assistant message. */
public final class MaxTurnsExceededException extends EngineException {
    public MaxTurnsExceededException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.MAX_TURNS_EXCEEDED, message, details, developerDetails);
    }

    public MaxTurnsExceededException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.MAX_TURNS_EXCEEDED, message, details, developerDetails, cause);
    }
}
