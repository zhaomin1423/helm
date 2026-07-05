package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when a tool call fails (execution error, invalid input, invalid output, or unknown tool). The stable
 * {@link ErrorCode} distinguishes the failure kind so HTTP callers and event consumers can react appropriately.
 */
public final class ToolExecutionException extends HelmException {

    public ToolExecutionException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.TOOL_EXECUTION_FAILED, message, details, developerDetails);
    }

    public ToolExecutionException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.TOOL_EXECUTION_FAILED, message, details, developerDetails, cause);
    }

    public ToolExecutionException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(code, message, details, developerDetails);
    }

    public ToolExecutionException(
            ErrorCode code,
            String message,
            Map<String, Object> details,
            Map<String, Object> developerDetails,
            Throwable cause) {
        super(code, message, details, developerDetails, cause);
    }

    /** Tool input does not satisfy the tool's declared schema; raised before user tool code runs. */
    public static ToolExecutionException inputInvalid(String tool, String reason) {
        return new ToolExecutionException(
                ErrorCode.TOOL_INPUT_INVALID, "Invalid tool input: " + reason, Map.of("tool", tool), Map.of());
    }

    /** Tool output is not serializable or violates the tool's declared output contract. */
    public static ToolExecutionException outputInvalid(String tool, String reason) {
        return new ToolExecutionException(
                ErrorCode.TOOL_OUTPUT_INVALID, "Invalid tool output: " + reason, Map.of("tool", tool), Map.of());
    }

    /** The model requested a tool that is not registered for this agent. */
    public static ToolExecutionException notFound(String tool) {
        return new ToolExecutionException(
                ErrorCode.TOOL_NOT_FOUND, "Tool not found: " + tool, Map.of("tool", tool), Map.of());
    }
}
