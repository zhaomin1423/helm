package io.agent.helm.core.tool;

import io.agent.helm.core.type.JsonSchema;
import java.util.Objects;

/**
 * Provider-facing description of a tool: a stable name, a human-readable description, and the JSON schema describing
 * accepted input. Advertised to model providers via {@link io.agent.helm.core.model.ModelRequest#tools()}.
 */
public record ToolDescriptor(String name, String description, JsonSchema inputSchema) {
    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        description = description == null ? "" : description;
        Objects.requireNonNull(inputSchema, "inputSchema");
    }

    public static ToolDescriptor from(Tool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool");
        return new ToolDescriptor(tool.name(), tool.description(), tool.inputSchema());
    }
}
