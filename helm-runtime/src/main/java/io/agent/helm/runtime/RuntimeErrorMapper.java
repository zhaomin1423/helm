package io.agent.helm.runtime;

import io.agent.helm.core.error.HelmException;
import java.util.LinkedHashMap;
import java.util.Map;

final class RuntimeErrorMapper {
    private RuntimeErrorMapper() {}

    static Map<String, Object> operationError(Throwable throwable) {
        return runtimeError(throwable, "RUNTIME_ERROR");
    }

    static Map<String, Object> workflowError(Throwable throwable) {
        return runtimeError(throwable, "WORKFLOW_FAILED");
    }

    private static Map<String, Object> runtimeError(Throwable throwable, String genericCode) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (throwable instanceof HelmException helmException) {
            error.put("code", helmException.code());
            error.put("details", EventRedactor.redact(Map.copyOf(helmException.details())));
        } else {
            error.put("code", genericCode);
            error.put("details", Map.of());
        }
        error.put("exception", throwable.getClass().getName());
        error.put("message", String.valueOf(throwable.getMessage()));
        return Map.copyOf(error);
    }
}
