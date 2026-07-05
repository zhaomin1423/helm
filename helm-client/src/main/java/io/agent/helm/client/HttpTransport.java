package io.agent.helm.client;

import java.util.concurrent.Flow;

/**
 * Internal transport abstraction so {@link DefaultHelmClient} stays HTTP-agnostic. The default implementation
 * {@link JdkHttpTransport} uses {@link java.net.http.HttpClient}; future variants (OkHttp, Spring WebClient) can drop
 * in without polluting {@link HelmClient}.
 *
 * <p>Implementations own resources (connection pools, executors) and must be {@link #close() closed} when no longer
 * needed.
 */
interface HttpTransport extends AutoCloseable {

    /** Sends a synchronous JSON request and returns the response body as a string plus status. */
    RawResponse send(String method, String path, Object body);

    /**
     * Sends a request expecting a {@code text/event-stream} response. Returns status and headers plus a
     * {@link Flow.Publisher} of body lines so the caller can consume SSE frames incrementally. Never retries.
     */
    StreamResponse sendStream(String method, String path, Object body);

    /** Releases the underlying HTTP client and its connection pool / executor. */
    @Override
    void close();
}
