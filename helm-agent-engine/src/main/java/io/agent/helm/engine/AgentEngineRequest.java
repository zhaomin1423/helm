package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.tool.ToolDescriptor;
import java.time.Duration;
import java.util.List;

public record AgentEngineRequest(
        ModelRef model,
        String instructions,
        List<ToolDescriptor> tools,
        List<HelmMessage> messages,
        ModelProvider provider,
        ToolExecutor toolExecutor,
        Duration timeout,
        int maxTurns,
        EngineEventListener listener) {

    public AgentEngineRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        listener = listener == null ? EngineEventListener.noop() : listener;
    }

    /** Backward-compatible constructor; listener defaults to no-op. */
    public AgentEngineRequest(
            ModelRef model,
            String instructions,
            List<ToolDescriptor> tools,
            List<HelmMessage> messages,
            ModelProvider provider,
            ToolExecutor toolExecutor,
            Duration timeout,
            int maxTurns) {
        this(model, instructions, tools, messages, provider, toolExecutor, timeout, maxTurns, null);
    }
}
