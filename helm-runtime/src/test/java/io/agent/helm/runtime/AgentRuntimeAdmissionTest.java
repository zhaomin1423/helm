package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.admission.AcquisitionResult;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.error.RateLimitExceededException;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.security.AuthorizationResult;
import org.junit.jupiter.api.Test;

/** Verifies admission (authorizer + rate limiter) runs before an operation is created. */
class AgentRuntimeAdmissionTest {

    @Test
    void denyAuthorizerRejectsPromptBeforeOperation() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(simpleAgent("assistant"))
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .authorizer((ctx, action, resource) -> AuthorizationResult.deny("not allowed"))
                .build();

        assertThatThrownBy(() -> runtime.prompt(new AgentPromptRequest("assistant", "i", "s", "hi")))
                .isInstanceOf(AuthorizationException.class);

        // No operation should have been persisted for a denied request.
        assertThat(runtime.listOperations()).isEmpty();
    }

    @Test
    void rateLimiterRejectsWhenExceeded() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(simpleAgent("assistant"))
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .rateLimiter(key -> AcquisitionResult.denied(1000))
                .build();

        assertThatThrownBy(() -> runtime.prompt(new AgentPromptRequest("assistant", "i", "s", "hi")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void allowAuthorizerAndUnlimitedRateLimiterLetPromptThrough() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(simpleAgent("assistant"))
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .authorizer((ctx, action, resource) -> AuthorizationResult.allow())
                .build();

        org.assertj.core.api.Assertions.assertThat(runtime.prompt(new AgentPromptRequest("assistant", "i", "s", "hi"))
                        .text())
                .isEqualTo("hi");
    }

    private static AgentDefinition simpleAgent(String name) {
        return new AgentDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentConfig configure(AgentContext context) {
                return AgentConfig.builder()
                        .model("fake/test")
                        .instructions("hi")
                        .build();
            }
        };
    }
}
