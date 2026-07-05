package io.agent.helm.runtime.testkit;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;

/**
 * Test fixtures for assembling an {@link AgentRuntime} with a {@link FakeProvider} + {@link InMemoryRuntimeStore} in
 * tests. External adapter authors depend on this module (test scope) to build deterministic runtimes without
 * re-declaring the boilerplate.
 */
public final class AgentRuntimeTestFactory {

    private AgentRuntimeTestFactory() {}

    /** Builds a runtime whose agent responds with the given scripted texts, one per prompt. */
    public static AgentRuntime withFakeResponses(
            String agentName, String model, String instructions, String... responses) {
        FakeProvider provider = new FakeProvider("fake");
        for (String response : responses) {
            provider.enqueue(
                    new ModelStreamEvent.ContentDelta(response), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        }
        return AgentRuntime.builder()
                .agent(simpleAgent(agentName, model, instructions))
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();
    }

    /** A minimal {@link AgentDefinition} for tests. */
    public static AgentDefinition simpleAgent(String name, String model, String instructions) {
        return new AgentDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentConfig configure(AgentContext context) {
                return AgentConfig.builder()
                        .model(model)
                        .instructions(instructions)
                        .build();
            }
        };
    }

    public static AgentPromptRequest prompt(String agent, String instance, String session, String text) {
        return new AgentPromptRequest(agent, instance, session, text);
    }
}
