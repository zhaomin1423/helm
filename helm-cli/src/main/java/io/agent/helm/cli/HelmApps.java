package io.agent.helm.cli;

import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared helpers for CLI subcommands: app loading and structured error reporting. */
final class HelmApps {
    private HelmApps() {}

    static HelmApp load(String className) {
        if (className == null || className.isBlank()) {
            throw new ValidationException("--app is required", Map.of(), Map.of());
        }
        try {
            Class<?> cls = Class.forName(
                    className.trim(), false, Thread.currentThread().getContextClassLoader());
            if (!HelmApp.class.isAssignableFrom(cls)) {
                throw new ValidationException(
                        "app class does not implement HelmApp", Map.of("class", className), Map.of());
            }
            return (HelmApp) cls.getDeclaredConstructor().newInstance();
        } catch (HelmException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(
                    "failed to load HelmApp",
                    Map.of("class", className),
                    Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    static Map<String, Object> errorBody(Throwable throwable) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (throwable instanceof HelmException helmException) {
            error.put("code", helmException.code());
            error.put("message", helmException.getMessage());
            error.put("details", helmException.details());
        } else {
            error.put("code", "INTERNAL_ERROR");
            error.put("message", String.valueOf(throwable.getMessage()));
        }
        return Map.of("error", error);
    }
}
