package io.agent.helm.core.error;

import java.util.Map;

public final class WorkflowNotFoundException extends HelmException {
    public WorkflowNotFoundException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("WORKFLOW_NOT_FOUND", message, details, developerDetails);
    }
}
