package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when a workflow run fails for a non-{@link HelmException} reason. {@link HelmException} causes are re-thrown
 * unchanged so callers and event consumers see the original code (e.g. {@code SESSION_BUSY}); all other throwables are
 * wrapped with {@link ErrorCode#WORKFLOW_FAILED} and the original cause chained.
 */
public final class WorkflowException extends HelmException {
    public WorkflowException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.WORKFLOW_FAILED, message, details, developerDetails);
    }

    public WorkflowException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.WORKFLOW_FAILED, message, details, developerDetails, cause);
    }
}
