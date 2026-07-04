package io.agent.helm.http.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Framework-neutral HTTP request: method, path, matched path params, headers, and body text. */
public record HelmHttpRequest(
        String method, String path, Map<String, String> pathParams, Map<String, List<String>> headers, String body) {
    public HelmHttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        pathParams = Map.copyOf(Objects.requireNonNull(pathParams, "pathParams"));
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = body == null ? "" : body;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
