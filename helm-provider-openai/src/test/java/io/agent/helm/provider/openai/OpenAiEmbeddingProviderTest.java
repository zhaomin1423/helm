package io.agent.helm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers {@link OpenAiEmbeddingProvider}: request shape, response parsing, error handling. */
class OpenAiEmbeddingProviderTest {

    private WireMockServer server;
    private OpenAiEmbeddingProvider provider;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        provider = OpenAiEmbeddingProvider.builder()
                .baseUrl("http://localhost:" + server.port() + "/v1")
                .apiKey("test-key")
                .model("text-embedding-3-small")
                .dimension(3)
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void embedParsesVectorFromResponse() {
        server.stubFor(
                post(urlEqualTo("/v1/embeddings")).willReturn(okJson("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}")));
        float[] vector = provider.embed("hello");
        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void dimensionFromConfiguration() {
        assertThat(provider.dimension()).isEqualTo(3);
    }

    @Test
    void httpErrorFails() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 429");
    }

    @Test
    void missingEmbeddingArrayFails() {
        server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(okJson("{\"data\":[]}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing embedding");
    }
}
