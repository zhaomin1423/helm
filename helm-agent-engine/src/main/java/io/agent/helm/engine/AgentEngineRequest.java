package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import java.time.Duration;
import java.util.List;

public record AgentEngineRequest(
        ModelRef model,
        String instructions,
        List<HelmMessage> messages,
        ModelProvider provider,
        ToolExecutor toolExecutor,
        Duration timeout,
        int maxTurns) {
    public AgentEngineRequest {
        messages = List.copyOf(messages);
    }
}
