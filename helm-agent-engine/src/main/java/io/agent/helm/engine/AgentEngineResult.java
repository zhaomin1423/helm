package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.TokenUsage;
import java.util.List;

public record AgentEngineResult(String text, List<HelmMessage> messages, TokenUsage totalUsage) {
    public AgentEngineResult {
        messages = List.copyOf(messages);
        totalUsage = totalUsage == null ? new TokenUsage(0, 0) : totalUsage;
    }

    /** Backward-compatible constructor; total usage defaults to zero. */
    public AgentEngineResult(String text, List<HelmMessage> messages) {
        this(text, messages, new TokenUsage(0, 0));
    }
}
