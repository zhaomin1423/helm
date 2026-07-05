package io.agent.helm.core.error;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all Helm runtime failures. Carries a stable {@link ErrorCode code} exposed to HTTP clients and
 * persisted in events, plus a {@code details} map (safe to surface) and a {@code developerDetails} map (may contain
 * sensitive diagnostic content). Subclasses must funnel through the {@code ErrorCode} form.
 */
public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;

    /**
     * Constructor using a registered {@link ErrorCode}.
     *
     * @param code the stable error code; never {@code null}.
     * @param message the human-readable message surfaced in error responses.
     * @param details safe-to-surface details; {@code null} is treated as empty.
     * @param developerDetails diagnostic details for logs/developers; {@code null} is treated as empty.
     */
    protected HelmException(
            ErrorCode code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = code.stable();
        this.details = copySafe(details);
        this.developerDetails = copySafe(developerDetails);
    }

    /**
     * Cause-chaining constructor using a registered {@link ErrorCode}.
     *
     * @param code the stable error code; never {@code null}.
     * @param message the human-readable message surfaced in error responses.
     * @param details safe-to-surface details; {@code null} is treated as empty.
     * @param developerDetails diagnostic details for logs/developers; {@code null} is treated as empty.
     * @param cause the underlying cause; may be {@code null}.
     */
    protected HelmException(
            ErrorCode code,
            String message,
            Map<String, Object> details,
            Map<String, Object> developerDetails,
            Throwable cause) {
        super(message, cause);
        this.code = code.stable();
        this.details = copySafe(details);
        this.developerDetails = copySafe(developerDetails);
    }

    /**
     * Defensive copy helper: tolerates {@code null} source and {@code null} keys/values by skipping null entries, then
     * returning an unmodifiable view. Unlike {@link Map#copyOf}, this never throws on null values. Used by record value
     * types across helm-core to normalize nullable map fields uniformly.
     */
    public static Map<String, Object> copySafe(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Stable code persisted in events and returned to HTTP clients. */
    public String code() {
        return code;
    }

    /** Safe-to-surface details; never {@code null}, always unmodifiable. */
    public Map<String, Object> details() {
        return details;
    }

    /** Developer-only diagnostic details; never {@code null}, always unmodifiable. */
    public Map<String, Object> developerDetails() {
        return developerDetails;
    }
}
