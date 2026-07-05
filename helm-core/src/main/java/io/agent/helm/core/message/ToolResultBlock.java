package io.agent.helm.core.message;

import io.agent.helm.core.tool.ToolResult;

/**
 * A {@link ContentBlock} carrying the result of a tool call. Use {@link #from(ToolResult)} to convert a
 * {@link ToolResult} into a block, and {@link #toResult()} to convert back; this routes field-copying through a single
 * factory so the two record shapes cannot drift apart.
 */
public record ToolResultBlock(String toolCallId, Object output, boolean error) implements ContentBlock {
    /** Builds a block from a {@link ToolResult}, copying all fields. */
    public static ToolResultBlock from(ToolResult result) {
        return new ToolResultBlock(result.toolCallId(), result.output(), result.error());
    }

    /** Converts this block back into a {@link ToolResult}. */
    public ToolResult toResult() {
        return new ToolResult(toolCallId, output, error);
    }
}
