package io.agent.helm.provider.anthropic;

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

final class AnthropicProviderContractTest extends ModelProviderContractTest {
    @RegisterExtension
    WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private AnthropicProvider provider;

    @BeforeEach
    void setUpProvider() {
        provider = AnthropicProvider.builder()
                .providerId("anthropic")
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
        return ModelRef.parse("anthropic/claude-3-5-sonnet-20241022");
    }

    @Override
    protected void prepareTerminalTextStream(String text, TokenUsage usage) {
        String body =
                """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":%d}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"%s"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":%d}}

                event: message_stop
                data: {"type":"message_stop"}

                """
                        .formatted(usage.inputTokens(), text, usage.outputTokens());
        stubMessages(body);
    }

    @Override
    protected void prepareToolCallStream(
            String toolCallId, String toolName, Object input, String finalText, TokenUsage usage) {
        String body =
                """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":%d}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"%s","name":"%s"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"message\\":\\"hi\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":%d}}

                event: message_stop
                data: {"type":"message_stop"}

                """
                        .formatted(usage.inputTokens(), toolCallId, toolName, usage.outputTokens());
        stubMessages(body);
    }

    @Override
    protected void prepareErrorStream() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"internal_error\"}}")));
    }

    private void stubMessages(String body) {
        wireMock.stubFor(
                post(urlEqualTo("/v1/messages")).willReturn(ok(body).withHeader("Content-Type", "text/event-stream")));
    }
}
