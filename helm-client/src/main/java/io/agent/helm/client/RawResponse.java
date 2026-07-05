package io.agent.helm.client;

import java.util.Map;

/** Raw HTTP response: status, body, headers. Used internally by transport and error mapper. */
record RawResponse(int status, String body, Map<String, String> headers) {
    RawResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
