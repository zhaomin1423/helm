package io.agent.helm.core.message;

public record ToolResultBlock(String toolCallId, Object output, boolean error) implements ContentBlock {}
