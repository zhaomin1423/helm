package io.agent.helm.core.error;

import java.util.Map;

/** A model turn exceeded the configured timeout. */
public final class TurnTimeoutException extends EngineException {
    public TurnTimeoutException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.ENGINE_TIMEOUT, message, details, developerDetails);
    }
}
