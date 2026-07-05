package io.agent.helm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agent.helm.core.error.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers {@link OpenAiEmbeddingProvider}: request shape, response parsing, error handling, dimension validation. */
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
    void httpError429MapsToRateLimited() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(ProviderException.class)
                .satisfies(t -> assertThat(((ProviderException) t).code()).isEqualTo(ProviderException.RATE_LIMITED));
    }

    @Test
    void httpError500MapsToProviderError() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"internal\"}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(ProviderException.class)
                .satisfies(t -> assertThat(((ProviderException) t).code()).isEqualTo(ProviderException.CODE))
                .satisfies(t -> assertThat(((ProviderException) t).details().get("status"))
                        .isEqualTo(500));
    }

    @Test
    void missingEmbeddingArrayFails() {
        server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(okJson("{\"data\":[]}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("missing embedding");
    }

    @Test
    void dimensionMismatchFails() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(okJson("{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4,0.5]}]}")));
        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("dimension mismatch")
                .satisfies(t -> {
                    ProviderException pe = (ProviderException) t;
                    assertThat(pe.details().get("expected")).isEqualTo(3);
                    assertThat(pe.details().get("actual")).isEqualTo(5);
                });
    }
}
