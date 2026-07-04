package io.agent.helm.core.agent;

import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.tool.Tool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AgentConfig(ModelRef model, String instructions, List<Tool<?, ?>> tools, Sandbox sandbox) {
    public AgentConfig {
        model = Objects.requireNonNull(model, "model");
        instructions = instructions == null ? "" : instructions;
        tools = validateTools(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<Tool<?, ?>> validateTools(List<Tool<?, ?>> tools) {
        Objects.requireNonNull(tools, "tools");
        Set<String> names = new HashSet<>();
        List<Tool<?, ?>> validated = new ArrayList<>(tools.size());
        for (Tool<?, ?> tool : tools) {
            Objects.requireNonNull(tool, "tool");
            String name = Objects.requireNonNull(tool.name(), "tool name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
            validated.add(tool);
        }
        return List.copyOf(validated);
    }

    public static final class Builder {
        private ModelRef model;
        private String instructions = "";
        private final List<Tool<?, ?>> tools = new ArrayList<>();
        private Sandbox sandbox;

        public Builder model(String model) {
            this.model = ModelRef.parse(model);
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder tool(Tool<?, ?> tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder sandbox(Sandbox sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(model, instructions, tools, sandbox);
        }
    }
}
