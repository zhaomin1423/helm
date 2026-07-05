package io.agent.helm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builder for {@link HelmClient}. Sensible defaults: 5s connect timeout, 30s request timeout,
 * {@link RetryPolicy#DEFAULT}, Jackson with {@code findAndRegisterModules()}, no auth headers. Use
 * {@link #bearerToken(String)} or {@link #headerInjector(Function)} to inject credentials; credentials are never logged
 * by the transport.
 */
public final class HelmClientBuilder {

    private URI baseUrl;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private Function<Map<String, String>, Map<String, String>> headerInjector = Function.identity();

    HelmClientBuilder() {}

    public HelmClientBuilder baseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public HelmClientBuilder baseUrl(String baseUrl) {
        return baseUrl(URI.create(baseUrl));
    }

    public HelmClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        return this;
    }

    public HelmClientBuilder requestTimeout(Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        return this;
    }

    /**
     * Injects {@code Authorization: Bearer <token>} on every request. Equivalent to {@code headerInjector(h ->
     * Map.of("Authorization", "Bearer " + token))}. Mutually exclusive with later {@link #headerInjector(Function)}
     * calls only in the order they are applied.
     */
    public HelmClientBuilder bearerToken(String token) {
        Objects.requireNonNull(token, "token");
        String value = "Bearer " + token;
        this.headerInjector = headers -> merge(headers, "Authorization", value);
        return this;
    }

    /**
     * Convenience: injects {@code X-Helm-Principal} and (when {@code attributes} is non-empty) one {@code X-Helm-<Key>}
     * header per entry, matching the principal header convention from authorizer design.
     */
    public HelmClientBuilder principalHeader(String principal, Map<String, String> attributes) {
        Objects.requireNonNull(principal, "principal");
        this.headerInjector = headers -> {
            Map<String, String> merged = new java.util.LinkedHashMap<>(headers);
            merged.put("X-Helm-Principal", principal);
            if (attributes != null) {
                attributes.forEach((k, v) -> merged.put("X-Helm-" + k, v));
            }
            return merged;
        };
        return this;
    }

    /**
     * Adds arbitrary headers to every request. The function receives the current header map (which may already contain
     * auth headers set by {@link #bearerToken(String)}) and returns the merged map to apply. Use this for custom auth
     * schemes.
     */
    public HelmClientBuilder headerInjector(Function<Map<String, String>, Map<String, String>> headerInjector) {
        this.headerInjector = Objects.requireNonNull(headerInjector, "headerInjector");
        return this;
    }

    public HelmClientBuilder retry(RetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        return this;
    }

    public HelmClientBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    public HelmClient build() {
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new IllegalStateException("baseUrl is required");
        }
        HelmClientConfig config =
                new HelmClientConfig(connectTimeout, requestTimeout, retryPolicy, objectMapper, headerInjector);
        JdkHttpTransport transport = new JdkHttpTransport(baseUrl, config);
        return new DefaultHelmClient(transport, objectMapper);
    }

    private static Map<String, String> merge(Map<String, String> headers, String key, String value) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(headers);
        merged.put(key, value);
        return merged;
    }
}
