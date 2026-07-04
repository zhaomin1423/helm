package io.agent.helm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelProviderContractTest;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

final class OpenAiProviderContractTest extends ModelProviderContractTest {
    @RegisterExtension
    WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private OpenAiProvider provider;

    @BeforeEach
    void setUpProvider() {
        provider = OpenAiProvider.builder()
                .providerId("openai")
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .build();
    }

    @Override
    protected ModelProvider provider() {
        return provider;
    }

    @Override
    protected ModelRef supportedModel() {
        return ModelRef.parse("openai/gpt-4.1");
    }

    @Override
    protected void prepareTerminalTextStream(String text, TokenUsage usage) {
        String body =
                """
                data: {"choices":[{"delta":{"content":"%s"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}

                data: [DONE]

                """
                        .formatted(text, usage.inputTokens(), usage.outputTokens());
        stubChatCompletions(body);
    }

    @Override
    protected void prepareToolCallStream(
            String toolCallId, String toolName, Object input, String finalText, TokenUsage usage) {
        String body =
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"%s","type":"function","function":{"name":"%s","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"message\\":\\"hi\\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}

                data: [DONE]

                """
                        .formatted(toolCallId, toolName, usage.inputTokens(), usage.outputTokens());
        stubChatCompletions(body);
    }

    @Override
    protected void prepareErrorStream() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"internal\"}}")));
    }

    private void stubChatCompletions(String body) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(ok(body).withHeader("Content-Type", "text/event-stream")));
    }
}
