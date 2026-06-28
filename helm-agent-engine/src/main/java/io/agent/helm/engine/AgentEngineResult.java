package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import java.util.List;

public record AgentEngineResult(String text, List<HelmMessage> messages) {
    public AgentEngineResult {
        messages = List.copyOf(messages);
    }
}
