package io.agent.helm.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;
import org.junit.jupiter.api.Test;

final class ToolDescriptorTest {
    record Echo(String message) {}

    @Test
    void fromDerivesNameDescriptionAndSchema() {
        Tool<Echo, String> tool = new Tool<>() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echoes input";
            }

            @Override
            public TypeDescriptor<Echo> inputType() {
                return new TypeDescriptor<>() {};
            }

            @Override
            public TypeDescriptor<String> outputType() {
                return TypeDescriptor.of(String.class);
            }

            @Override
            public String execute(ToolContext context, Echo input) {
                return input.message();
            }
        };
        ToolDescriptor descriptor = ToolDescriptor.from(tool);

        assertThat(descriptor.name()).isEqualTo("echo");
        assertThat(descriptor.description()).isEqualTo("Echoes input");
        assertThat(descriptor.inputSchema().type()).isEqualTo("object");
        assertThat(descriptor.inputSchema().properties()).containsKey("message");
    }

    @Test
    void defaultsDescriptionToEmptyAndUsesSchema() {
        Tool<String, String> tool = new Tool<>() {
            @Override
            public String name() {
                return "t";
            }

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
        };
        ToolDescriptor descriptor = ToolDescriptor.from(tool);

        assertThat(descriptor.description()).isEqualTo("");
        assertThat(descriptor.inputSchema()).isEqualTo(JsonSchema.string());
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ToolDescriptor("  ", "d", JsonSchema.string()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
