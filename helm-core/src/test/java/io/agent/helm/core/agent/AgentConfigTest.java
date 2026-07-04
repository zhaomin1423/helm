package io.agent.helm.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.TypeDescriptor;
import org.junit.jupiter.api.Test;

final class AgentConfigTest {
    @Test
    void buildsAgentConfigWithModelAndInstructions() {
        AgentConfig config = AgentConfig.builder()
                .model("openai/gpt-4.1")
                .instructions("You are helpful.")
                .build();

        assertThat(config.model()).isEqualTo(ModelRef.parse("openai/gpt-4.1"));
        assertThat(config.instructions()).isEqualTo("You are helpful.");
        assertThat(config.tools()).isEmpty();
    }

    @Test
    void requiresModel() {
        assertThatThrownBy(() -> AgentConfig.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model");
    }

    @Test
    void directConstructorRequiresModel() {
        assertThatThrownBy(() -> new AgentConfig(null, "", java.util.List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model");
    }

    @Test
    void normalizesNullInstructionsToEmptyString() {
        AgentConfig config =
                AgentConfig.builder().model("openai/gpt-4.1").instructions(null).build();

        assertThat(config.instructions()).isEmpty();
    }

    @Test
    void rejectsNullTool() {
        assertThatThrownBy(() ->
                        AgentConfig.builder().model("openai/gpt-4.1").tool(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void rejectsNullToolName() {
        assertThatThrownBy(() -> AgentConfig.builder()
                        .model("openai/gpt-4.1")
                        .tool(new TestTool(null))
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tool name");
    }

    @Test
    void rejectsBlankToolName() {
        assertThatThrownBy(() -> AgentConfig.builder()
                        .model("openai/gpt-4.1")
                        .tool(new TestTool(" "))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool name");
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> AgentConfig.builder()
                        .model("openai/gpt-4.1")
                        .tool(new TestTool("echo"))
                        .tool(new TestTool("echo"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tool name: echo");
    }

    private record TestTool(String name) implements Tool<String, String> {
        @Override
        public TypeDescriptor<String> inputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String execute(ToolContext context, String input) {
            return input;
        }
    }
}
