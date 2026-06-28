package io.agent.helm.core.error;

import java.util.Map;

public final class ToolExecutionException extends HelmException {
    public ToolExecutionException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("TOOL_EXECUTION_FAILED", message, details, developerDetails);
    }
}
