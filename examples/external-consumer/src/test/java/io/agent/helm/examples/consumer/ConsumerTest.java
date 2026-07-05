package io.agent.helm.examples.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import org.junit.jupiter.api.Test;

/** Verifies an external consumer can use the Helm public API surface end-to-end. */
class ConsumerTest {

    @Test
    void consumesHelmApiEndToEnd() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new ConsumerApp.HelloAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();
        PromptResult result = runtime.prompt(new AgentPromptRequest("hello", "i1", "default", "hi"));
        assertThat(result.text()).isEqualTo("hi");
        assertThat(runtime.getOperation(result.operationId())).isPresent();
    }
}
