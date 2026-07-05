package io.agent.helm.http.core;

import java.util.List;
import java.util.Map;

/** Framework-neutral HTTP response: status, headers, and body text. */
public record HelmHttpResponse(int status, Map<String, List<String>> headers, String body) {
    public HelmHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }

    public static HelmHttpResponse ok(String body) {
        return json(200, body);
    }

    public static HelmHttpResponse accepted(String body) {
        return json(202, body);
    }

    public static HelmHttpResponse json(int status, String body) {
        return new HelmHttpResponse(status, Map.of("Content-Type", List.of("application/json")), body);
    }
}
