package io.agent.helm.core.store;

/**
 * Lifecycle status of an operation. The terminal states are {@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED},
 * and {@link #INTERRUPTED}; non-terminal states include {@link #QUEUED} and {@link #RUNNING}.
 */
public enum OperationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    /**
     * Returns {@code true} for terminal states after which no further status transition is expected.
     *
     * @return {@code true} if this status is {@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED}, or
     *     {@link #INTERRUPTED}.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }
}
