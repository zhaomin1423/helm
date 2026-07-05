package io.agent.helm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxCommand;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ContractDefensiveCopyTest {
    @Test
    void agentConfigToolsAreDefensivelyCopiedAndUnmodifiable() {
        Tool<String, String> tool = new Tool<>() {
            @Override
            public String name() {
                return "echo";
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

        List<Tool<?, ?>> tools = new ArrayList<>(List.of(tool));
        AgentConfig config = new AgentConfig(ModelRef.parse("openai/gpt-4.1"), "", tools, null);
        tools.clear();

        assertThat(config.tools()).containsExactly(tool);
        assertThatThrownBy(() -> config.tools().add(tool)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentConfigBuilderCopiesTools() {
        Tool<String, String> tool = new Tool<>() {
            @Override
            public String name() {
                return "echo";
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

        AgentConfig config =
                AgentConfig.builder().model("openai/gpt-4.1").tool(tool).build();

        assertThat(config.tools()).hasSize(1);
        assertThatThrownBy(() -> config.tools().add(tool)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentSessionStateMessagesAreDefensivelyCopiedAndUnmodifiable() {
        List<HelmMessage> messages = new ArrayList<>();
        messages.add(HelmMessage.user("hello"));

        AgentSessionState state = new AgentSessionState(
                "session-1",
                "agent",
                "instance",
                "session",
                1,
                messages,
                Instant.parse("2026-06-28T00:00:00Z"),
                Instant.parse("2026-06-28T00:00:01Z"));

        messages.clear();

        assertThat(state.messages()).hasSize(1);
        assertThatThrownBy(() -> state.messages().add(HelmMessage.user("again")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolInputSchemaDelegatesToJsonSchemaFromInputType() {
        Tool<String, String> tool = new Tool<>() {
            @Override
            public String name() {
                return "echo";
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

        assertThat(tool.inputSchema()).isEqualTo(JsonSchema.from(TypeDescriptor.of(String.class)));
    }

    @Test
    void sandboxCommandCopiesCollections() {
        List<String> argv = new ArrayList<>(List.of("echo", "hello"));
        Map<String, String> environment = new HashMap<>(Map.of("A", "1"));

        SandboxCommand command = new SandboxCommand(argv, Duration.ofSeconds(5), environment);

        argv.add("ignored");
        environment.put("B", "2");

        assertThat(command.argv()).containsExactly("echo", "hello");
        assertThat(command.environment()).containsEntry("A", "1");
        assertThatThrownBy(() -> command.argv().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.environment().put("C", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void runtimeEventRecordCopiesPayload() {
        Map<String, Object> payload = new HashMap<>(Map.of("message", "ok"));

        var record = new RuntimeEventRecord(
                "id",
                "op",
                "run",
                1,
                RuntimeEventType.OPERATION_STARTED,
                payload,
                Instant.parse("2026-06-28T00:00:00Z"));

        payload.put("other", "value");

        assertThat(record.payload()).containsEntry("message", "ok");
        assertThatThrownBy(() -> record.payload().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void runtimeEventRecordSkipsNullPayloadEntries() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("keep", "v");
        payload.put("drop", null);
        payload.put(null, "skip");

        RuntimeEventRecord record = new RuntimeEventRecord(
                "id",
                "op",
                "run",
                1,
                RuntimeEventType.OPERATION_STARTED,
                payload,
                Instant.parse("2026-06-28T00:00:00Z"));

        assertThat(record.payload()).containsOnlyKeys("keep");
        assertThatThrownBy(() -> record.payload().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void operationRecordCopiesErrorMap() {
        Map<String, Object> error = new HashMap<>(Map.of("message", "boom"));

        OperationRecord record = new OperationRecord(
                "id",
                "session",
                HelmAction.PROMPT,
                OperationStatus.FAILED,
                null,
                null,
                error,
                Instant.parse("2026-06-28T00:00:00Z"),
                Instant.parse("2026-06-28T00:00:01Z"));

        error.put("details", "ignored");

        assertThat(record.error()).containsEntry("message", "boom");
        assertThatThrownBy(() -> record.error().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void workflowRunRecordCopiesErrorMap() {
        Map<String, Object> error = new HashMap<>(Map.of("message", "boom"));

        WorkflowRunRecord record = new WorkflowRunRecord(
                "id",
                "workflow",
                WorkflowRunStatus.FAILED,
                null,
                null,
                error,
                Instant.parse("2026-06-28T00:00:00Z"),
                Instant.parse("2026-06-28T00:00:01Z"));

        error.put("details", "ignored");

        assertThat(record.error()).containsEntry("message", "boom");
        assertThatThrownBy(() -> record.error().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentConfigDefaultsSandboxToDisabledWhenNull() {
        AgentConfig config = AgentConfig.builder().model("openai/gpt-4.1").build();

        assertThat(config.sandbox()).isNotNull().isSameAs(Sandbox.disabled());
    }

    @Test
    void agentConfigDirectConstructorDefaultsSandboxWhenNull() {
        AgentConfig config = new AgentConfig(ModelRef.parse("openai/gpt-4.1"), "", List.of(), null);

        assertThat(config.sandbox()).isSameAs(Sandbox.disabled());
    }
}
