package io.agent.helm.core.error;

import java.util.Map;

/** A model provider stream failed (transport error, protocol failure, or unexpected termination). */
public final class ModelStreamException extends EngineException {
    public ModelStreamException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.MODEL_STREAM_FAILED, message, details, developerDetails);
    }

    public ModelStreamException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.MODEL_STREAM_FAILED, message, details, developerDetails, cause);
    }
}
