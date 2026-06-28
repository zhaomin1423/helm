package io.agent.helm.core.error;

import java.util.Map;

public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;

    protected HelmException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Map<String, Object> developerDetails() {
        return developerDetails;
    }
}
