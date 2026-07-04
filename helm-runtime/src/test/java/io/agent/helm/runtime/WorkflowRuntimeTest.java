package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkflowRuntimeTest {
    record Input(String text) {}

    record Output(String text) {}

    @Test
    void invokesWorkflowAndStoresRun() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("summary"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        WorkflowRuntime runtime = WorkflowRuntime.builder()
                .workflow(new SummarizeWorkflow())
                .provider(provider)
                .store(store)
                .build();

        WorkflowRunHandle<Output> handle =
                runtime.invoke(new WorkflowInvokeRequest<>("summarize", new Input("long text")));

        assertThat(handle.result().text()).isEqualTo("summary");
        assertThat(runtime.getRun(handle.runId()))
                .isPresent()
                .get()
                .extracting(WorkflowRunRecord::status, WorkflowRunRecord::output)
                .containsExactly(WorkflowRunStatus.SUCCEEDED, handle.result());
        assertThat(runtime.listRuns()).extracting(WorkflowRunRecord::id).containsExactly(handle.runId());
        assertThat(runtime.getRunEvents(handle.runId()))
                .extracting(RuntimeEventRecord::type)
                .containsExactly(RuntimeEventType.WORKFLOW_STARTED.type(), RuntimeEventType.WORKFLOW_SUCCEEDED.type());
    }

    @Test
    void unknownWorkflowRunInspectionReturnsEmptyResults() {
        WorkflowRuntime runtime = WorkflowRuntime.builder().build();

        assertThat(runtime.getRun("missing")).isEmpty();
        assertThat(runtime.getRunEvents("missing")).isEmpty();
        assertThat(runtime.listRuns()).isEmpty();
    }

    @Test
    void storesFailedRunWhenWorkflowConfigThrows() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        WorkflowRuntime runtime = WorkflowRuntime.builder()
                .workflow(new ConfigFailWorkflow())
                .store(store)
                .build();

        assertThatThrownBy(() -> runtime.invoke(new WorkflowInvokeRequest<>("broken", new Input("long text"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow failed");

        WorkflowRunRecord run = runtime.listRuns().stream()
                .filter(record -> record.status() == WorkflowRunStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(runtime.getRun(run.id())).isPresent();
        assertThat(run.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(run.error().keySet()).containsExactlyInAnyOrder("code", "details", "exception", "message");
        assertThat(run.error()).containsEntry("code", "SESSION_BUSY");
        assertThat(run.error()).containsEntry("exception", SessionBusyException.class.getName());
        assertThat(run.error()).containsEntry("details", Map.of("phase", "config"));
    }

    @Test
    void storesRedactedWorkflowFailureEventPayload() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        WorkflowRuntime runtime = WorkflowRuntime.builder()
                .workflow(new SecretConfigWorkflow())
                .store(store)
                .build();

        assertThatThrownBy(() -> runtime.invoke(new WorkflowInvokeRequest<>("secret", new Input("long text"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow failed");

        WorkflowRunRecord run = runtime.listRuns().stream()
                .filter(record -> record.status() == WorkflowRunStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(run.error().keySet()).containsExactlyInAnyOrder("code", "details", "exception", "message");
        assertThat(run.error()).containsEntry("code", "SESSION_BUSY");
        assertThat(run.error()).containsEntry("exception", SessionBusyException.class.getName());
        assertThat(run.error())
                .containsEntry(
                        "details",
                        Map.of(
                                "headers", Map.of("Authorization", "[REDACTED]"),
                                "environment", Map.of("PASSWORD", "[REDACTED]"),
                                "safe", "value"));

        var events = runtime.getRunEvents(run.id());
        assertThat(events)
                .extracting(RuntimeEventRecord::type)
                .containsExactly(RuntimeEventType.WORKFLOW_STARTED.type(), RuntimeEventType.WORKFLOW_FAILED.type());
        var failedEvent = events.stream()
                .filter(event -> event.type().equals(RuntimeEventType.WORKFLOW_FAILED.type()))
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.payload())
                .containsEntry("code", "SESSION_BUSY")
                .containsEntry("exception", SessionBusyException.class.getName())
                .containsEntry("message", "config boom")
                .containsEntry(
                        "details",
                        Map.of(
                                "headers", Map.of("Authorization", "[REDACTED]"),
                                "environment", Map.of("PASSWORD", "[REDACTED]"),
                                "safe", "value"));
        assertThat(failedEvent.payload()).doesNotContainKey("developerDetails");
    }

    private static final class Agent implements AgentDefinition {
        @Override
        public String name() {
            return "summarizer";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Summarize.")
                    .build();
        }
    }

    private static final class SummarizeWorkflow implements WorkflowDefinition<Input, Output> {
        @Override
        public String name() {
            return "summarize";
        }

        @Override
        public WorkflowConfig config() {
            return WorkflowConfig.of(new Agent());
        }

        @Override
        public TypeDescriptor<Input> inputType() {
            return TypeDescriptor.of(Input.class);
        }

        @Override
        public TypeDescriptor<Output> outputType() {
            return TypeDescriptor.of(Output.class);
        }

        @Override
        public Output run(WorkflowContext<Input> context) {
            PromptResult result =
                    context.harness().session("default").prompt(context.input().text());
            return new Output(result.text());
        }
    }

    private static final class ConfigFailWorkflow implements WorkflowDefinition<Input, Output> {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public WorkflowConfig config() {
            throw new SessionBusyException("config boom", Map.of("phase", "config"), Map.of("secret", "x"));
        }

        @Override
        public TypeDescriptor<Input> inputType() {
            return TypeDescriptor.of(Input.class);
        }

        @Override
        public TypeDescriptor<Output> outputType() {
            return TypeDescriptor.of(Output.class);
        }

        @Override
        public Output run(WorkflowContext<Input> context) {
            return new Output("never");
        }
    }

    private static final class SecretConfigWorkflow implements WorkflowDefinition<Input, Output> {
        @Override
        public String name() {
            return "secret";
        }

        @Override
        public WorkflowConfig config() {
            throw new SessionBusyException(
                    "config boom",
                    Map.of(
                            "headers", Map.of("Authorization", "Bearer abc"),
                            "environment", Map.of("PASSWORD", "pw"),
                            "safe", "value",
                            "developerDetails", Map.of("token", "hide")),
                    Map.of("developerDetails", Map.of("token", "hide"), "apiKey", "secret"));
        }

        @Override
        public TypeDescriptor<Input> inputType() {
            return TypeDescriptor.of(Input.class);
        }

        @Override
        public TypeDescriptor<Output> outputType() {
            return TypeDescriptor.of(Output.class);
        }

        @Override
        public Output run(WorkflowContext<Input> context) {
            return new Output("never");
        }
    }
}
