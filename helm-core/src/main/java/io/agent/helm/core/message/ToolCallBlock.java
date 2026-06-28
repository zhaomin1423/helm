package io.agent.helm.core.message;

public record ToolCallBlock(String id, String name, Object input) implements ContentBlock {}
