package io.agent.helm.core.error;

/**
 * Stable error code registry for {@link HelmException}. Codes are stable identifiers exposed to HTTP clients and
 * persisted in events. Add new codes only by appending to this enum and updating {@code docs/contracts/error-codes.md}.
 *
 * @since 0.2.0
 */
public enum ErrorCode {
    // Agent / workflow / provider / resource lookup
    AGENT_NOT_FOUND,
    WORKFLOW_NOT_FOUND,
    PROVIDER_NOT_FOUND,
    OPERATION_NOT_FOUND,
    SESSION_NOT_FOUND,

    // Provider failures
    PROVIDER_ERROR,
    PROVIDER_RATE_LIMITED,
    PROVIDER_TIMEOUT,

    // Engine / context
    CONTEXT_OVERFLOW,
    SESSION_BUSY,
    ENGINE_TIMEOUT,
    MAX_TURNS_EXCEEDED,
    MODEL_STREAM_FAILED,
    ENGINE_INTERRUPTED,

    // Tool / sandbox / validation
    TOOL_EXECUTION_FAILED,
    TOOL_INPUT_INVALID,
    TOOL_OUTPUT_INVALID,
    TOOL_NOT_FOUND,
    SANDBOX_ERROR,
    VALIDATION_FAILED,

    // Persistence
    PERSISTENCE_ERROR,

    // Generic / uncategorized
    INTERNAL_ERROR,

    // Authorization / admission
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    OPERATION_REJECTED,

    // Durable scale (M11)
    LEASE_LOST,
    RECOVERY_FAILED;

    /** Returns the stable string form persisted in events and returned to HTTP clients. */
    public String stable() {
        return name();
    }
}
