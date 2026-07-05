package io.agent.helm.examples.consumer;

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
 * Minimal application consuming the Helm public API: defines an agent, wires a FakeProvider + in-memory store, and runs
 * a prompt. Demonstrates that an external consumer can use the published artifacts without depending on internals.
 */
public final class ConsumerApp {

    public static void main(String[] args) {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new HelloAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();
        var result = runtime.prompt(new AgentPromptRequest("hello", "i1", "default", "hi"));
        System.out.println(result.text());
    }

    public static final class HelloAgent implements AgentDefinition {
        @Override
        public String name() {
            return "hello";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Be helpful.")
                    .build();
        }
    }
}
