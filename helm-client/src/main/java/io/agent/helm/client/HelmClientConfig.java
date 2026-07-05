package io.agent.helm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Immutable configuration consumed by {@link JdkHttpTransport}. */
record HelmClientConfig(
        Duration connectTimeout,
        Duration requestTimeout,
        RetryPolicy retryPolicy,
        ObjectMapper objectMapper,
        Function<Map<String, String>, Map<String, String>> headerInjector) {

    HelmClientConfig {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
        objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        headerInjector = headerInjector == null ? Function.identity() : headerInjector;
    }
}
