package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.ArrayList;
import java.util.List;

public final class AgentEngine {
    private final TurnRunner turnRunner = new TurnRunner();

    public AgentEngineResult run(AgentEngineRequest request) {
        List<HelmMessage> messages = new ArrayList<>(request.messages());
        for (int turn = 0; turn < request.maxTurns(); turn++) {
            TurnResult result = turnRunner.run(
                    request.provider(),
                    new ModelRequest(request.model(), request.instructions(), messages, request.timeout()));

            if (result.toolCalls().isEmpty()) {
                HelmMessage assistant = HelmMessage.assistant(result.text());
                messages.add(assistant);
                return new AgentEngineResult(result.text(), messages);
            }

            for (ModelStreamEvent.ToolCallRequested toolCall : result.toolCalls()) {
                messages.add(new HelmMessage(
                        Role.ASSISTANT, List.of(new ToolCallBlock(toolCall.id(), toolCall.name(), toolCall.input()))));
                Object output = request.toolExecutor().execute("engine", toolCall.name(), toolCall.input());
                messages.add(new HelmMessage(Role.TOOL, List.of(new ToolResultBlock(toolCall.id(), output, false))));
            }
        }

        throw new IllegalStateException("Agent loop exceeded max turns");
    }
}
