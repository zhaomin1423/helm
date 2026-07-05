package io.agent.helm.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agent.helm.core.error.ErrorCode;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.memory.EmbeddingProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link EmbeddingProvider} backed by the OpenAI Embeddings API (or any OpenAI-compatible endpoint). Sends a
 * synchronous {@code POST /embeddings} per text and parses {@code data[0].embedding}. The dimension is configured up
 * front so callers can match the model (e.g. 1536 for {@code text-embedding-3-small}, 3072 for {@code -3-large}).
 *
 * <p><b>Base URL convention.</b> {@code baseUrl} includes the {@code /v1} segment; a trailing {@code /} is stripped.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final Duration timeout;
    private final HttpClient httpClient;

    private OpenAiEmbeddingProvider(
            String baseUrl, String apiKey, String model, int dimension, Duration timeout, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.timeout = timeout;
        this.httpClient = httpClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public float[] embed(String text) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("input", text);
            body.put("model", model);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 400) {
                throw mapHttpError(status, response.body());
            }
            JsonNode node = MAPPER.readTree(response.body());
            JsonNode embedding = node.path("data").path(0).path("embedding");
            if (!embedding.isArray()) {
                throw new ProviderException(
                        ErrorCode.PROVIDER_ERROR,
                        "openai embeddings response missing embedding array",
                        Map.of("provider", "openai"),
                        Map.of("body", response.body()));
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (vector.length != dimension) {
                throw new ProviderException(
                        ErrorCode.PROVIDER_ERROR,
                        "openai embeddings dimension mismatch",
                        Map.of("provider", "openai", "expected", dimension, "actual", vector.length),
                        Map.of("model", model));
            }
            return vector;
        } catch (IOException e) {
            throw new ProviderException(
                    ErrorCode.PROVIDER_ERROR,
                    "openai embeddings request failed",
                    Map.of("provider", "openai"),
                    Map.of("message", String.valueOf(e.getMessage())),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    ErrorCode.PROVIDER_ERROR,
                    "openai embeddings interrupted",
                    Map.of("provider", "openai"),
                    Map.of(),
                    e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private static ProviderException mapHttpError(int status, String body) {
        ErrorCode code = ErrorCode.PROVIDER_ERROR;
        if (status == 429) {
            code = ErrorCode.PROVIDER_RATE_LIMITED;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", "openai");
        details.put("status", status);
        return new ProviderException(code, "openai embeddings api error", details, Map.of("body", body));
    }

    public static final class Builder {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "text-embedding-3-small";
        private int dimension = 1536;
        private Duration timeout = Duration.ofSeconds(30);
        private HttpClient httpClient = HttpClient.newHttpClient();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl);
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey);
            return this;
        }

        public Builder model(String model) {
            this.model = Objects.requireNonNull(model);
            return this;
        }

        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient);
            return this;
        }

        public OpenAiEmbeddingProvider build() {
            if (apiKey == null) {
                throw new IllegalArgumentException("apiKey is required");
            }
            String normalized = baseUrl;
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return new OpenAiEmbeddingProvider(normalized, apiKey, model, dimension, timeout, httpClient);
        }
    }
}
