package io.agent.helm.http.core;

/** A registered route: HTTP method, a path pattern with {@code {param}} segments, and a handler. */
public record HttpRoute(String method, String pattern, HelmHttpHandler handler) {
    public HttpRoute {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        handler.getClass();
    }
}
