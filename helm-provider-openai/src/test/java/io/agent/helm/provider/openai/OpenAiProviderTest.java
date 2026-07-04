package io.agent.helm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.core.type.JsonSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

final class OpenAiProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = OpenAiProvider.builder()
                .providerId("openai")
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .build();
    }

    @Test
    void postsModelStreamMessagesAndAuthHeader() throws Exception {
        stubTerminal("hello");
        drain(provider.stream(request("hi", List.of())));

        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(requests).hasSize(1);
        JsonNode body = MAPPER.readTree(requests.get(0).getBodyAsString());
        assertThat(body.get("model").asText()).isEqualTo("gpt-4.1");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("hi");
        assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void postsToolsWhenPresent() throws Exception {
        stubTerminal("ok");
        ToolDescriptor tool = new ToolDescriptor(
                "echo",
                "Echoes input",
                JsonSchema.object(java.util.Map.of("message", JsonSchema.string()), List.of("message")));
        drain(provider.stream(request("use echo", List.of(tool))));

        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        JsonNode body = MAPPER.readTree(requests.get(0).getBodyAsString());
        assertThat(body.get("tools").get(0).get("type").asText()).isEqualTo("function");
        assertThat(body.get("tools").get(0).get("function").get("name").asText())
                .isEqualTo("echo");
    }

    @Test
    void mapsRateLimitToProviderRateLimited() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")));

        Throwable error = captureError(request("hi", List.of()));

        assertThat(error).isInstanceOf(ProviderException.class);
        assertThat(((ProviderException) error).code()).isEqualTo(ProviderException.RATE_LIMITED);
    }

    @Test
    void mapsTimeoutToProviderTimeout() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                        ok("data: [DONE]\n\n").withFixedDelay(2000).withHeader("Content-Type", "text/event-stream")));

        ModelRequest req = new ModelRequest(
                ModelRef.parse("openai/gpt-4.1"),
                "",
                List.of(),
                List.of(HelmMessage.user("hi")),
                Duration.ofMillis(100));

        Throwable error = captureError(req);

        assertThat(error).isInstanceOf(ProviderException.class);
        assertThat(((ProviderException) error).code()).isEqualTo(ProviderException.TIMEOUT);
    }

    @Test
    void doesNotLeakApiKeyInErrorDetails() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"internal\"}")));

        Throwable error = captureError(request("hi", List.of()));

        assertThat(error).isInstanceOf(ProviderException.class);
        ProviderException pe = (ProviderException) error;
        assertDoesNotContainKey(pe.getMessage());
        pe.details().values().forEach(value -> assertThat(String.valueOf(value)).doesNotContain("test-key"));
        pe.developerDetails().values().forEach(value -> assertThat(String.valueOf(value))
                .doesNotContain("test-key"));
    }

    private void stubTerminal(String text) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                        ok("""
                        data: {"choices":[{"delta":{"content":"%s"}}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                        data: [DONE]

                        """
                                        .formatted(text))
                                .withHeader("Content-Type", "text/event-stream")));
    }

    private static void assertDoesNotContainKey(String value) {
        assertThat(value).doesNotContain("test-key");
    }

    private static ModelRequest request(String text, List<ToolDescriptor> tools) {
        return new ModelRequest(
                ModelRef.parse("openai/gpt-4.1"), "", tools, List.of(HelmMessage.user(text)), Duration.ofSeconds(5));
    }

    private static List<ModelStreamEvent> drain(Flow.Publisher<ModelStreamEvent> publisher)
            throws InterruptedException {
        CopyOnWriteArrayList<ModelStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ModelStreamEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        return events;
    }

    private Throwable captureError(ModelRequest request) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        provider.stream(request).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ModelStreamEvent event) {}

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return error.get();
    }
}
