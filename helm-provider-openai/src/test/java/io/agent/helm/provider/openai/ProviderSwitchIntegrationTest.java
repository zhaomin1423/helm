package io.agent.helm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the M2 exit criterion: the same agent definition runs through {@link AgentRuntime} against either
 * {@link FakeProvider} or a WireMock-backed OpenAI provider, with consistent admission/persistence behavior (only the
 * model output text differs).
 */
final class ProviderSwitchIntegrationTest {
    @RegisterExtension
    WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Test
    void fakeAndOpenAiProvidersBothRunAgentPrompt() {
        PromptResult fakeResult = runWithFake();
        PromptResult openAiResult = runWithOpenAi();

        assertThat(fakeResult.text()).isEqualTo("fake-response");
        assertThat(openAiResult.text()).isEqualTo("openai-response");
        assertThat(fakeResult.operationId()).isNotEqualTo(openAiResult.operationId());
    }

    private PromptResult runWithFake() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("fake-response"),
                new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(switchableAgent("fake/test"))
                .provider(provider)
                .store(store)
                .build();

        PromptResult result = runtime.prompt(new AgentPromptRequest("switch", "instance-1", "default", "hi"));
        assertThat(store.loadOperation(result.operationId())).isPresent();
        return result;
    }

    private PromptResult runWithOpenAi() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                        ok("""
                        data: {"choices":[{"delta":{"content":"openai-response"}}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                        data: [DONE]

                        """)
                                .withHeader("Content-Type", "text/event-stream")));
        OpenAiProvider provider = OpenAiProvider.builder()
                .providerId("openai")
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .build();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(switchableAgent("openai/gpt-4.1"))
                .provider(provider)
                .store(store)
                .build();

        PromptResult result = runtime.prompt(new AgentPromptRequest("switch", "instance-1", "default", "hi"));
        assertThat(store.loadOperation(result.operationId())).isPresent().hasValueSatisfying(op -> assertThat(
                        op.status())
                .isEqualTo(io.agent.helm.core.store.OperationStatus.SUCCEEDED));
        return result;
    }

    private static AgentDefinition switchableAgent(String model) {
        return new AgentDefinition() {
            @Override
            public String name() {
                return "switch";
            }

            @Override
            public AgentConfig configure(AgentContext context) {
                return AgentConfig.builder()
                        .model(model)
                        .instructions("Be helpful.")
                        .build();
            }
        };
    }
}
