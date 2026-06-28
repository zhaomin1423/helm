package io.agent.helm.core.agent;

import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AgentConfig(ModelRef model, String instructions, List<Tool<?, ?>> tools, Sandbox sandbox) {
    public AgentConfig {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    public static Builder builder() {
        return new Builder();
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
