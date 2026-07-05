package io.agent.helm.core.error;

import java.util.Map;

public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;

    /** Constructor using a registered {@link ErrorCode}. */
    protected HelmException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = code.stable();
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    /** Constructor validating a raw code string against the {@link ErrorCode} registry. */
    protected HelmException(
            String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = validate(code);
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    private static String validate(String code) {
        try {
            return ErrorCode.valueOf(code).stable();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown HelmException code: " + code, e);
        }
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
