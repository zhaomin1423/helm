package io.agent.helm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.WorkflowRuntime;

/** Test assembly: a FakeProvider-backed echo agent and an upper-casing workflow. */
public final class TestHelmApp implements HelmApp {
    private final FakeProvider provider = new FakeProvider("fake");
    private final AgentRuntime agentRuntime = AgentRuntime.builder()
            .agent(new EchoAgent())
            .provider(provider)
            .store(new InMemoryRuntimeStore())
            .build();
    private final WorkflowRuntime workflowRuntime = WorkflowRuntime.builder()
            .workflow(new UpperWorkflow())
            .provider(provider)
            .store(new InMemoryRuntimeStore())
            .build();

    @Override
    public AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    @Override
    public WorkflowRuntime workflowRuntime() {
        return workflowRuntime;
    }

    public static final class EchoAgent implements AgentDefinition {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Echo.")
                    .build();
        }
    }

    public static final class UpperWorkflow implements WorkflowDefinition<Object, String> {
        @Override
        public String name() {
            return "upper";
        }

        @Override
        public WorkflowConfig config() {
            return WorkflowConfig.of(new EchoAgent());
        }

        @Override
        public TypeDescriptor<Object> inputType() {
            return new TypeDescriptor<>() {};
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String run(WorkflowContext<Object> context) {
            Object input = context.input();
            if (input instanceof JsonNode node) {
                return node.path("text").asText("").toUpperCase();
            }
            return String.valueOf(input).toUpperCase();
        }
    }
}
