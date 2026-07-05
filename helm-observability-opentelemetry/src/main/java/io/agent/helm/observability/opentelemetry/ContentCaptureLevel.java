package io.agent.helm.observability.opentelemetry;

/**
 * Controls how much event/tool content is captured into span attributes. Content here means prompt text, model response
 * text, and tool input/output objects — never credentials, PII, or {@link Redact}-annotated fields, which are always
 * redacted regardless of capture level.
 *
 * <p>Default is {@link #METADATA_ONLY} per the roadmap "safe defaults" principle. Content never enters metrics labels —
 * metrics labels are structural enum values only.
 */
public enum ContentCaptureLevel {
    /** Only structural metadata: ids, types, durations, counts, statuses, error codes. No content. */
    METADATA_ONLY,

    /** Metadata plus truncated content (prompt/response/tool io) truncated to 200 characters. */
    SUMMARY,

    /** Full content as span attributes. Use only in local development or troubleshooting. */
    FULL
}
