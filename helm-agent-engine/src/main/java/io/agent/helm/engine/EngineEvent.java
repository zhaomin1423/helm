package io.agent.helm.engine;

import io.agent.helm.core.model.TokenUsage;

/**
 * Engine-level events emitted during the agent loop. The runtime bridges these to
 * {@link io.agent.helm.core.event.RuntimeEventRecord} via an {@link EngineEventListener}.
 */
public sealed interface EngineEvent
        permits EngineEvent.TurnStarted,
                EngineEvent.TurnSucceeded,
                EngineEvent.TurnFailed,
                EngineEvent.ModelStarted,
                EngineEvent.ModelSucceeded,
                EngineEvent.ModelFailed,
                EngineEvent.ToolStarted,
                EngineEvent.ToolSucceeded,
                EngineEvent.ToolFailed {

    int turnIndex();

    record TurnStarted(int turnIndex) implements EngineEvent {}

    record TurnSucceeded(int turnIndex, TokenUsage usage) implements EngineEvent {}

    record TurnFailed(int turnIndex, String code, String message) implements EngineEvent {}

    record ModelStarted(int turnIndex) implements EngineEvent {}

    record ModelSucceeded(int turnIndex, TokenUsage usage) implements EngineEvent {}

    record ModelFailed(int turnIndex, String code, String message) implements EngineEvent {}

    record ToolStarted(int turnIndex, String toolName, String toolCallId) implements EngineEvent {}

    record ToolSucceeded(int turnIndex, String toolName, String toolCallId) implements EngineEvent {}

    record ToolFailed(int turnIndex, String toolName, String toolCallId, String code, String message)
            implements EngineEvent {}
}
