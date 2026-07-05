package io.agent.helm.client;

/**
 * Internal transport abstraction so {@link DefaultHelmClient} stays HTTP-agnostic. The default implementation
 * {@link JdkHttpTransport} uses {@link java.net.http.HttpClient}; future variants (OkHttp, Spring WebClient) can drop
 * in without polluting {@link HelmClient}.
 */
interface HttpTransport {

    /** Sends a synchronous JSON request and returns the response body as a string plus status. */
    RawResponse send(String method, String path, Object body);

    /** Sends a request expecting a {@code text/event-stream} response; no retry. */
    RawResponse sendStream(String method, String path, Object body);
}
