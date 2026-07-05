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
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JDK 21 {@link java.net.http.HttpClient}-backed transport. Injects headers via the configured
 * {@link HelmClientConfig#headerInjector()}, serializes JSON bodies with the configured {@link ObjectMapper}, and
 * retries idempotent failures per {@link RetryPolicy}. Credentials and request bodies are never logged.
 *
 * <p>The {@link HttpClient} owns a connection pool and a selector/executor thread; {@link #close()} must be called when
 * the transport is no longer needed to avoid leaking those resources until JVM exit.
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
        return executeWithRetry(method, path, body);
    }

    @Override
    public StreamResponse sendStream(String method, String path, Object body) {
        try {
            return doSendStream(method, path, body);
        } catch (IOException e) {
            throw new RuntimeException("HTTP stream request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP stream request interrupted: " + method + " " + path, e);
        }
    }

    @Override
    public void close() {
        // Java 21 HttpClient implements AutoCloseable; close() shuts down the selector and executor.
        try {
            http.close();
        } catch (Exception e) {
            // Best-effort cleanup; swallow to avoid masking primary failures on close.
        }
    }

    private RawResponse executeWithRetry(String method, String path, Object body) {
        RetryPolicy policy = config.retryPolicy();
        int maxAttempts = policy.maxAttempts();
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RawResponse response = doSendOnce(method, path, body);
                if (attempt < maxAttempts && shouldRetry(response.status(), method)) {
                    Duration sleep = retryDelay(response, attempt);
                    sleep(sleep);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastIo = e;
                if (attempt < maxAttempts) {
                    try {
                        sleep(backoff(attempt, policy));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(
                                "HTTP request interrupted during retry backoff: " + method + " " + path, ie);
                    }
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

    private RawResponse doSendOnce(String method, String path, Object body) throws IOException, InterruptedException {
        URI uri = baseUrl.resolve(path);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(config.requestTimeout());

        // The injector owns all headers, including Accept and Content-Type, so they appear exactly once.
        Map<String, String> baseHeaders = new LinkedHashMap<>();
        baseHeaders.put("Accept", "application/json");
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

    private StreamResponse doSendStream(String method, String path, Object body)
            throws IOException, InterruptedException {
        URI uri = baseUrl.resolve(path);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(config.requestTimeout());

        Map<String, String> baseHeaders = new LinkedHashMap<>();
        baseHeaders.put("Accept", "text/event-stream");
        baseHeaders.put("Content-Type", "application/json");
        Map<String, String> merged = config.headerInjector().apply(baseHeaders);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            req.header(entry.getKey(), entry.getValue());
        }

        HttpRequest.BodyPublisher bodyPublisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
        req.method(method, bodyPublisher);

        // sendAsync + ofPublisher() returns as soon as the response status/headers are available; the body publisher
        // emits byte-buffer chunks incrementally as they arrive from the network. We transform those chunks into lines
        // so callers can consume SSE frames without buffering the entire body.
        HttpResponse<java.util.concurrent.Flow.Publisher<java.util.List<java.nio.ByteBuffer>>> response =
                http.send(req.build(), BodyHandlers.ofPublisher());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k, v.get(0));
            }
        });
        Flow.Publisher<String> linePublisher = new LinePublisherAdapter(response.body());
        return new StreamResponse(response.statusCode(), headers, linePublisher);
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

    private static void sleep(Duration duration) throws InterruptedException {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        Thread.sleep(duration);
    }
}
