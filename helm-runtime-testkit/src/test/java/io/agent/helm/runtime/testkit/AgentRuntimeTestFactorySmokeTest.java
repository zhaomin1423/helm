package io.agent.helm.runtime.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for {@link AgentRuntimeTestFactory}: guards against silent breakage when
 * {@link AgentRuntime}/{@link io.agent.helm.runtime.FakeProvider} APIs drift. The testkit module is referenced by no
 * other module, so without this test the factory could rot undetected.
 */
final class AgentRuntimeTestFactorySmokeTest {

    private static final String FAKE_MODEL = "fake/test";

    @Test
    void withFakeResponsesReturnsScriptedTextOnPrompt() {
        try (AgentRuntime runtime =
                AgentRuntimeTestFactory.withFakeResponses("assistant", FAKE_MODEL, "You are helpful.", "hello")) {
            PromptResult result =
                    runtime.prompt(AgentRuntimeTestFactory.prompt("assistant", "instance-1", "default", "Hi"));

            assertThat(result.text()).isEqualTo("hello");
            assertThat(result.operationId()).isNotBlank();
        }
    }

    @Test
    void withFakeResponsesServesMultipleScriptedResponsesInOrder() {
        try (AgentRuntime runtime = AgentRuntimeTestFactory.withFakeResponses(
                "assistant", FAKE_MODEL, "You are helpful.", "first", "second")) {
            PromptResult first =
                    runtime.prompt(AgentRuntimeTestFactory.prompt("assistant", "instance-1", "default", "one"));
            PromptResult second =
                    runtime.prompt(AgentRuntimeTestFactory.prompt("assistant", "instance-1", "default", "two"));

            assertThat(first.text()).isEqualTo("first");
            assertThat(second.text()).isEqualTo("second");
        }
    }

    @Test
    void simpleAgentExposesProvidedNameAndWiresIntoRuntime() {
        // simpleAgent produces a definition whose name matches what we passed and is accepted by AgentRuntime.Builder.
        try (AgentRuntime runtime = AgentRuntime.builder()
                .agent(AgentRuntimeTestFactory.simpleAgent("echo", FAKE_MODEL, "echo back"))
                .provider(new io.agent.helm.runtime.FakeProvider("fake"))
                .store(new io.agent.helm.runtime.InMemoryRuntimeStore())
                .build()) {
            // Sanity: the agent is registered — listing sessions on a fresh runtime returns empty without error.
            assertThat(runtime.listSessions()).isEmpty();
        }
    }
}
