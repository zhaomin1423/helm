package io.agent.helm.core.model;

import java.util.Objects;

public record ModelRef(String providerId, String modelId) {
    public ModelRef {
        providerId = Objects.requireNonNull(providerId, "providerId");
        modelId = Objects.requireNonNull(modelId, "modelId");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
    }

    public static ModelRef parse(String value) {
        String[] parts = value.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Model reference must use provider/model format");
        }
        return new ModelRef(parts[0], parts[1]);
    }

    public String value() {
        return providerId + "/" + modelId;
    }
}
