package io.agent.helm.core.agent;

import io.agent.helm.core.annotation.Preview;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.model.TokenUsage;
import java.util.Objects;

/**
 * Incremental events emitted by {@code promptStream}. Streaming callers (HTTP SSE, CLI, client SDK) consume these as
 * the model produces output, rather than waiting for the full operation to complete. {@code @Preview} streaming surface
 * is being validated; the event set may change before 1.0.
 */
@Preview
public sealed interface PromptStreamEvent
        permits PromptStreamEvent.ContentDelta,
                PromptStreamEvent.ToolCallRequested,
                PromptStreamEvent.ToolResultReady,
                PromptStreamEvent.TurnEnded,
                PromptStreamEvent.OperationCompleted,
                PromptStreamEvent.OperationFailed {

    /** A chunk of model-generated text. */
    record ContentDelta(String text) implements PromptStreamEvent {}

    /**
     * The model requested a tool call. Use {@link #toBlock()} to convert to a {@link ToolCallBlock} without field
     * drift.
     */
    record ToolCallRequested(String id, String name, Object input) implements PromptStreamEvent {
        public ToolCallRequested {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }

        /** Converts this event into a {@link ToolCallBlock} for assembly into a message. */
        public ToolCallBlock toBlock() {
            return new ToolCallBlock(id, name, input);
        }
    }

    /** A tool call completed and its result is available. */
    record ToolResultReady(String id, String name, Object output) implements PromptStreamEvent {}

    /** A model turn completed (terminal assistant message or before tool calls). */
    record TurnEnded(int turnIndex, TokenUsage usage) implements PromptStreamEvent {}

    /** The operation completed successfully; no further events will be emitted. */
    record OperationCompleted(String operationId, String text, TokenUsage totalUsage) implements PromptStreamEvent {}

    /** The operation failed; no further events will be emitted. */
    record OperationFailed(String operationId, String code, String message) implements PromptStreamEvent {}
}
