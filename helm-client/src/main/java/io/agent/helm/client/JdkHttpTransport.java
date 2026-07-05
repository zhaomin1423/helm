package io.agent.helm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JDK 21 {@link java.net.http.HttpClient}-backed transport. Injects headers via the configured
 * {@link HelmClientConfig#headerInjector()}, serializes JSON bodies with the configured {@link ObjectMapper}, and
 * retries idempotent failures per {@link RetryPolicy}. Credentials and request bodies are never logged.
 */
final class JdkHttpTransport implements HttpTransport {

    private final URI baseUrl;
    private final HelmClientConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    JdkHttpTransport(URI baseUrl, HelmClientConfig config) {
        this.baseUrl = baseUrl;
        this.config = config;
        this.mapper = config.objectMapper();
        this.http =
                HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    @Override
    public RawResponse send(String method, String path, Object body) {
        return executeWithRetry(method, path, body, false);
    }

    @Override
    public RawResponse sendStream(String method, String path, Object body) {
        return executeWithRetry(method, path, body, true);
    }

    private RawResponse executeWithRetry(String method, String path, Object body, boolean stream) {
        RetryPolicy policy = config.retryPolicy();
        int maxAttempts = policy.maxAttempts();
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RawResponse response = doSendOnce(method, path, body, stream);
                if (attempt < maxAttempts && shouldRetry(response.status(), method)) {
                    Duration sleep = retryDelay(response, attempt);
                    sleep(sleep);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastIo = e;
                if (attempt < maxAttempts) {
                    sleep(backoff(attempt, policy));
                    continue;
                }
                throw new RuntimeException("HTTP request failed: " + method + " " + path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("HTTP request interrupted: " + method + " " + path, e);
            }
        }
        if (lastIo != null) {
            throw new RuntimeException("HTTP request failed after retries: " + method + " " + path, lastIo);
        }
        throw new IllegalStateException("retry loop exited without response");
    }

    private RawResponse doSendOnce(String method, String path, Object body, boolean stream)
            throws IOException, InterruptedException {
        URI uri = baseUrl.resolve(path);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("Content-Type", "application/json");

        Map<String, String> baseHeaders = new LinkedHashMap<>();
        baseHeaders.put("Accept", stream ? "text/event-stream" : "application/json");
        baseHeaders.put("Content-Type", "application/json");
        Map<String, String> merged = config.headerInjector().apply(baseHeaders);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            req.header(entry.getKey(), entry.getValue());
        }

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
        req.method(method, publisher);

        HttpResponse<String> response = http.send(req.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k, v.get(0));
            }
        });
        return new RawResponse(response.statusCode(), response.body() == null ? "" : response.body(), headers);
    }

    private static boolean shouldRetry(int status, String method) {
        if (!(method.equals("GET") || method.equals("HEAD"))) {
            return false;
        }
        return status == 408 || status == 429 || status == 503 || status == 504;
    }

    private Duration retryDelay(RawResponse response, int attempt) {
        if (response.status() == 429) {
            String retryAfter = response.headers().get("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    Duration cap = config.retryPolicy().retryAfterCap();
                    Duration parsed = Duration.ofSeconds(seconds);
                    if (!cap.isZero()) {
                        parsed = parsed.compareTo(cap) > 0 ? cap : parsed;
                    }
                    return parsed;
                } catch (NumberFormatException ignored) {
                    // fall through to backoff
                }
            }
        }
        return backoff(attempt, config.retryPolicy());
    }

    private static Duration backoff(int attempt, RetryPolicy policy) {
        if (policy.initialBackoff().isZero()) {
            return Duration.ZERO;
        }
        long multiplier = 1L << (attempt - 1);
        long millis = policy.initialBackoff().toMillis() * multiplier;
        // Add jitter (±25%) so concurrent clients don't synchronize.
        long jitter = ThreadLocalRandom.current().nextLong(-millis / 4, millis / 4 + 1);
        Duration result = Duration.ofMillis(Math.max(0, millis + jitter));
        return result.compareTo(policy.maxBackoff()) > 0 ? policy.maxBackoff() : result;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
