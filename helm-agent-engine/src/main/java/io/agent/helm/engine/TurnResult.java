package io.agent.helm.engine;

import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.List;

record TurnResult(String text, List<ModelStreamEvent.ToolCallRequested> toolCalls, TokenUsage usage) {
    TurnResult {
        toolCalls = List.copyOf(toolCalls);
        usage = usage == null ? new TokenUsage(0, 0) : usage;
    }
}
