package io.agent.helm.core.store;

import io.agent.helm.core.annotation.Preview;
import io.agent.helm.core.model.TokenUsage;
import java.time.Instant;
import java.util.List;

/**
 * Turn-granularity journal for mid-turn recovery. SPI anchor for the post-GA durable scale runtime; the default runtime
 * persists session state after each turn via {@link RuntimeStore#saveSession} and does not yet use this
 * journal. @Preview M11 post-GA; the SPI shape is being validated and may change before implementation.
 */
@Preview
public interface TurnJournal {

    /** Appends a completed turn entry. */
    void append(TurnEntry entry);

    /** Returns the last {@code n} completed turns for an operation, oldest first. */
    List<TurnEntry> tail(String operationId, int lastN);

    /** Truncates the journal to entries with {@code turnIndex <= upToTurnIndex}, discarding half-finished turns. */
    void truncate(String operationId, long upToTurnIndex);

    /** A completed turn's outcome. */
    record TurnEntry(String operationId, long turnIndex, TurnStatus status, TokenUsage usage, Instant completedAt) {}

    /** Outcome of a journaled turn. */
    enum TurnStatus {
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }
}
