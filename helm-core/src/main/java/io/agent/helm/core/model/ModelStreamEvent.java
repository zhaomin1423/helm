package io.agent.helm.core.model;

public sealed interface ModelStreamEvent
        permits ModelStreamEvent.ContentDelta, ModelStreamEvent.ToolCallRequested, ModelStreamEvent.Completed {
    record ContentDelta(String text) implements ModelStreamEvent {}

    record ToolCallRequested(String id, String name, Object input) implements ModelStreamEvent {}

    record Completed(TokenUsage usage) implements ModelStreamEvent {}
}
