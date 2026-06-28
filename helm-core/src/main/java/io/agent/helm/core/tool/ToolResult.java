package io.agent.helm.core.tool;

public record ToolResult(String toolCallId, Object output, boolean error) {}
