package io.agent.helm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.error.AgentNotFoundException;
import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.error.ContextOverflowException;
import io.agent.helm.core.error.ErrorCode;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.error.ProviderNotFoundException;
import io.agent.helm.core.error.RateLimitExceededException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.ValidationException;
import io.agent.helm.core.error.WorkflowNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restores an HTTP error response ({@code {"error":{code,message,details}}}) to the matching {@link HelmException}
 * subclass. Passes through {@code code}/{@code details}; the {@code message} becomes the exception message. Unmapped
 * server codes fall back to {@link ErrorCode#INTERNAL_ERROR} with the original server code preserved in
 * {@code details.serverCode} for operators.
 */
final class ClientErrorMapper {

    private final ObjectMapper mapper;

    ClientErrorMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns {@code true} for the generic {@code 404 NOT_FOUND} that {@code getOperation}/{@code getRun} map to empty.
     */
    boolean isNotFound(RawResponse response) {
        if (response.status() != 404) {
            return false;
        }
        ErrorBody error = parse(response);
        return "NOT_FOUND".equals(error.code());
    }

    HelmException toException(RawResponse response) {
        ErrorBody error = parse(response);
        return toException(response.status(), error);
    }

    HelmException toException(int status, ErrorBody error) {
        String code = error.code();
        String message = error.message();
        Map<String, Object> details = error.details();
        return switch (code) {
            case "AGENT_NOT_FOUND" -> new AgentNotFoundException(message, details, Map.of());
            case "WORKFLOW_NOT_FOUND" -> new WorkflowNotFoundException(message, details, Map.of());
            case "VALIDATION_FAILED" -> new ValidationException(message, details, Map.of());
            case "SESSION_BUSY" -> new SessionBusyException(message, details, Map.of());
            case "CONTEXT_OVERFLOW" -> new ContextOverflowException(message, details, Map.of());
            case "PROVIDER_RATE_LIMITED" -> new RateLimitExceededException(message, details, Map.of());
            case "RATE_LIMITED" -> new RateLimitExceededException(message, details, Map.of());
            case "PROVIDER_TIMEOUT" -> new ProviderException(ErrorCode.PROVIDER_TIMEOUT, message, details, Map.of());
            case "PROVIDER_ERROR" -> new ProviderException(ErrorCode.PROVIDER_ERROR, message, details, Map.of());
            case "PROVIDER_NOT_FOUND" -> new ProviderNotFoundException(message, details, Map.of());
            case "UNAUTHORIZED" -> AuthorizationException.unauthorized(message, details);
            case "FORBIDDEN" -> AuthorizationException.forbidden(message, details);
            default -> unmapped(code, message, details);
        };
    }

    /**
     * Preserves the original server code in {@code details.serverCode} while mapping to the closest stable
     * {@link ErrorCode}. The default choice is {@link ErrorCode#INTERNAL_ERROR}; specific unmapped codes that map more
     * naturally to another registered code can be added here.
     */
    private HelmException unmapped(String serverCode, String message, Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put("serverCode", serverCode);
        return new HelmException(ErrorCode.INTERNAL_ERROR, message, merged, Map.of()) {};
    }

    ErrorBody parse(RawResponse response) {
        return parse(response.body(), response.status());
    }

    ErrorBody parse(String body, int status) {
        if (body == null || body.isBlank()) {
            return new ErrorBody("INTERNAL_ERROR", "HTTP " + status, Map.of());
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode err = root.path("error");
            JsonNode codeNode = err.path("code");
            JsonNode messageNode = err.path("message");
            JsonNode detailsNode = err.path("details");
            String code = codeNode.isMissingNode() ? "INTERNAL_ERROR" : codeNode.asText();
            String message = messageNode.isMissingNode() ? "" : messageNode.asText();
            @SuppressWarnings("unchecked")
            Map<String, Object> details = mapper.convertValue(detailsNode, Map.class);
            if (details == null) {
                details = Map.of();
            }
            return new ErrorBody(code, message, details);
        } catch (Exception e) {
            return new ErrorBody("INTERNAL_ERROR", "failed to parse error body: " + e.getMessage(), Map.of());
        }
    }

    record ErrorBody(String code, String message, Map<String, Object> details) {}
}
