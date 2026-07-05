package io.agent.helm.core.model;

import io.agent.helm.core.message.ToolCallBlock;
import java.util.Objects;

/**
 * Streaming events emitted by {@link io.agent.helm.core.model.ModelProvider#stream}. The runtime reduces a stream into
 * a final response, dispatching tool calls and accumulating token usage.
 */
public sealed interface ModelStreamEvent
        permits ModelStreamEvent.ContentDelta, ModelStreamEvent.ToolCallRequested, ModelStreamEvent.Completed {
    /** A chunk of model-generated text. */
    record ContentDelta(String text) implements ModelStreamEvent {}

    /**
     * The model requested a tool call. Use {@link #toBlock()} to convert to a {@link ToolCallBlock} without field
     * drift.
     */
    record ToolCallRequested(String id, String name, Object input) implements ModelStreamEvent {
        public ToolCallRequested {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }

        /** Converts this event into a {@link ToolCallBlock} for assembly into a message. */
        public ToolCallBlock toBlock() {
            return new ToolCallBlock(id, name, input);
        }
    }

    /**
     * The stream terminated.
     *
     * @param usage final token accounting; never {@code null}.
     * @param finishReason provider-specific termination reason (e.g. {@code stop}, {@code length}, {@code tool_calls});
     *     may be {@code null} when the provider does not report one.
     */
    record Completed(TokenUsage usage, String finishReason) implements ModelStreamEvent {
        /** Backwards-compatible constructor; {@code finishReason} defaults to {@code null}. */
        public Completed(TokenUsage usage) {
            this(usage, null);
        }
    }
}
