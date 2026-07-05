package io.agent.helm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.error.AgentNotFoundException;
import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.error.ContextOverflowException;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.error.RateLimitExceededException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.ValidationException;
import io.agent.helm.core.error.WorkflowNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ClientErrorMapper} code → exception mapping: every server code in {@code HttpErrors.statusFor} must
 * restore to the matching {@link HelmException} subclass with code/details pass-through.
 */
final class ClientErrorMapperTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ClientErrorMapper errorMapper = new ClientErrorMapper(mapper);

    @Test
    void agentNotFoundRestoresToAgentNotFoundException() {
        HelmException exception = map(404, "AGENT_NOT_FOUND", "no agent", Map.of("agent", "echo"));
        assertThat(exception).isInstanceOf(AgentNotFoundException.class);
        assertThat(exception.code()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(exception.getMessage()).isEqualTo("no agent");
        assertThat(exception.details()).containsEntry("agent", "echo");
    }

    @Test
    void workflowNotFoundRestoresToWorkflowNotFoundException() {
        HelmException exception = map(404, "WORKFLOW_NOT_FOUND", "no workflow", Map.of());
        assertThat(exception).isInstanceOf(WorkflowNotFoundException.class);
        assertThat(exception.code()).isEqualTo("WORKFLOW_NOT_FOUND");
    }

    @Test
    void validationFailedRestoresToValidationException() {
        HelmException exception = map(400, "VALIDATION_FAILED", "missing field", Map.of("field", "text"));
        assertThat(exception).isInstanceOf(ValidationException.class);
        assertThat(exception.details()).containsEntry("field", "text");
    }

    @Test
    void sessionBusyRestoresToSessionBusyException() {
        HelmException exception = map(409, "SESSION_BUSY", "busy", Map.of());
        assertThat(exception).isInstanceOf(SessionBusyException.class);
        assertThat(exception.code()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void contextOverflowRestoresToContextOverflowException() {
        HelmException exception = map(413, "CONTEXT_OVERFLOW", "too long", Map.of());
        assertThat(exception).isInstanceOf(ContextOverflowException.class);
        assertThat(exception.code()).isEqualTo("CONTEXT_OVERFLOW");
    }

    @Test
    void providerRateLimitedRestoresToRateLimitExceededException() {
        HelmException exception = map(429, "PROVIDER_RATE_LIMITED", "slow down", Map.of());
        assertThat(exception).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void rateLimitedRestoresToRateLimitExceededException() {
        HelmException exception = map(429, "RATE_LIMITED", "slow down", Map.of());
        assertThat(exception).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void providerTimeoutRestoresToProviderExceptionWithTimeoutCode() {
        HelmException exception = map(504, "PROVIDER_TIMEOUT", "timeout", Map.of());
        assertThat(exception).isInstanceOf(ProviderException.class);
        assertThat(exception.code()).isEqualTo("PROVIDER_TIMEOUT");
    }

    @Test
    void providerErrorRestoresToProviderExceptionWithErrorCode() {
        HelmException exception = map(502, "PROVIDER_ERROR", "boom", Map.of());
        assertThat(exception).isInstanceOf(ProviderException.class);
        assertThat(exception.code()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void unauthorizedRestoresToAuthorizationException() {
        HelmException exception = map(401, "UNAUTHORIZED", "no token", Map.of());
        assertThat(exception).isInstanceOf(AuthorizationException.class);
        assertThat(exception.code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void forbiddenRestoresToAuthorizationException() {
        HelmException exception = map(403, "FORBIDDEN", "denied", Map.of());
        assertThat(exception).isInstanceOf(AuthorizationException.class);
        assertThat(exception.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unknownCodeFallsBackToHelmException() {
        HelmException exception = map(500, "INTERNAL_ERROR", "kaboom", Map.of());
        assertThat(exception).isInstanceOf(HelmException.class);
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(exception.getMessage()).isEqualTo("kaboom");
    }

    @Test
    void unmappedServerCodePreservedInDetailsServerCode() {
        HelmException exception = map(500, "SOMETHING_NEW", "unknown failure", Map.of("traceId", "abc"));
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(exception.details()).containsEntry("serverCode", "SOMETHING_NEW");
        assertThat(exception.details()).containsEntry("traceId", "abc");
    }

    @Test
    void internalErrorCodeAlsoPreservesServerCode() {
        HelmException exception = map(500, "INTERNAL_ERROR", "kaboom", Map.of());
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(exception.details()).containsEntry("serverCode", "INTERNAL_ERROR");
    }

    @Test
    void isNotFoundReturnsTrueForGeneric404NotFound() {
        RawResponse response = new RawResponse(404, errorBody("NOT_FOUND", "missing", Map.of()), Map.of());
        assertThat(errorMapper.isNotFound(response)).isTrue();
    }

    @Test
    void isNotFoundReturnsFalseForAgentNotFound() {
        RawResponse response = new RawResponse(404, errorBody("AGENT_NOT_FOUND", "no agent", Map.of()), Map.of());
        assertThat(errorMapper.isNotFound(response)).isFalse();
    }

    @Test
    void isNotFoundReturnsFalseForSuccessResponse() {
        RawResponse response = new RawResponse(200, "{}", Map.of());
        assertThat(errorMapper.isNotFound(response)).isFalse();
    }

    @Test
    void emptyBodyFallsBackToInternalErrorCode() {
        RawResponse response = new RawResponse(500, "", Map.of());
        HelmException exception = errorMapper.toException(response);
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(exception.getMessage()).contains("500");
    }

    @Test
    void malformedBodyFallsBackToInternalErrorCode() {
        RawResponse response = new RawResponse(500, "not-json", Map.of());
        HelmException exception = errorMapper.toException(response);
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void toExceptionThrowsHelmExceptionSubclassNotWrapped() {
        RawResponse response = new RawResponse(409, errorBody("SESSION_BUSY", "busy", Map.of()), Map.of());
        assertThatThrownBy(() -> {
                    throw errorMapper.toException(response);
                })
                .isInstanceOf(SessionBusyException.class);
    }

    private HelmException map(int status, String code, String message, Map<String, Object> details) {
        RawResponse response = new RawResponse(status, errorBody(code, message, details), Map.of());
        return errorMapper.toException(response);
    }

    private String errorBody(String code, String message, Map<String, Object> details) {
        try {
            return mapper.writeValueAsString(
                    Map.of("error", Map.of("code", code, "message", message, "details", details)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
