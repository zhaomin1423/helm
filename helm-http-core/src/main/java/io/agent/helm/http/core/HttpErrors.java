package io.agent.helm.http.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agent.helm.core.error.HelmException;
import java.util.List;
import java.util.Map;

/** Maps framework errors to the unified HTTP error response {@code {"error":{code,message,details}}}. */
public final class HttpErrors {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private HttpErrors() {}

    public static HelmHttpResponse notFound(String path) {
        return errorResponse(404, "NOT_FOUND", "no route for " + path, Map.of());
    }

    public static HelmHttpResponse toResponse(Throwable throwable) {
        if (throwable instanceof HelmException helmException) {
            return errorResponse(
                    statusFor(helmException.code()),
                    helmException.code(),
                    helmException.getMessage(),
                    helmException.details());
        }
        return errorResponse(500, "INTERNAL_ERROR", "internal error", Map.of());
    }

    public static HelmHttpResponse errorResponse(int status, String code, String message, Map<String, Object> details) {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        error.set("details", MAPPER.valueToTree(details));
        try {
            return new HelmHttpResponse(
                    status, Map.of("Content-Type", List.of("application/json")), MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            return new HelmHttpResponse(
                    status,
                    Map.of("Content-Type", List.of("application/json")),
                    "{\"error\":{\"code\":\"" + code + "\"}}");
        }
    }

    public static int statusFor(String code) {
        return switch (code) {
            case "AGENT_NOT_FOUND",
                    "WORKFLOW_NOT_FOUND",
                    "OPERATION_NOT_FOUND",
                    "SESSION_NOT_FOUND",
                    "TOOL_NOT_FOUND" -> 404;
            case "VALIDATION_FAILED", "TOOL_INPUT_INVALID" -> 400;
            case "UNAUTHORIZED" -> 401;
            case "FORBIDDEN" -> 403;
            case "SESSION_BUSY" -> 409;
            case "CONTEXT_OVERFLOW" -> 413;
            case "PROVIDER_RATE_LIMITED", "RATE_LIMITED" -> 429;
            case "PROVIDER_TIMEOUT", "ENGINE_TIMEOUT" -> 504;
            case "PROVIDER_NOT_FOUND",
                    "PROVIDER_ERROR",
                    "MODEL_STREAM_FAILED",
                    "ENGINE_INTERRUPTED",
                    "MAX_TURNS_EXCEEDED" -> 502;
            default -> 500;
        };
    }
}
