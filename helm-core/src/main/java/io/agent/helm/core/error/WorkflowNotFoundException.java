package io.agent.helm.core.error;

import java.util.Map;

public final class WorkflowNotFoundException extends HelmException {
    public WorkflowNotFoundException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.WORKFLOW_NOT_FOUND, message, details, developerDetails);
    }

    public WorkflowNotFoundException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.WORKFLOW_NOT_FOUND, message, details, developerDetails, cause);
    }
}
