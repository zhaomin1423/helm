package io.agent.helm.engine;

import io.agent.helm.core.error.EngineException;
import io.agent.helm.core.error.MaxTurnsExceededException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.core.type.JsonSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the agent loop: model turn → tool calls → model turn, until a terminal assistant message is produced or
 * {@code maxTurns} is exceeded. Emits {@link EngineEvent}s via the request's {@link EngineEventListener}, validates
 * tool input/output, and aggregates token usage across turns.
 */
public final class AgentEngine {
    private final TurnRunner turnRunner = new TurnRunner();

    public AgentEngineResult run(AgentEngineRequest request) {
        EngineEventListener listener = request.listener();
        List<HelmMessage> messages = new ArrayList<>(request.messages());
        long inputTokens = 0;
        long outputTokens = 0;

        for (int turn = 0; turn < request.maxTurns(); turn++) {
            listener.onEvent(new EngineEvent.TurnStarted(turn));
            try {
                listener.onEvent(new EngineEvent.ModelStarted(turn));
                TurnResult result = turnRunner.run(
                        request.provider(),
                        new ModelRequest(
                                request.model(), request.instructions(), request.tools(), messages, request.timeout()));
                TokenUsage usage = result.usage();
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
                listener.onEvent(new EngineEvent.ModelSucceeded(turn, usage));

                if (result.toolCalls().isEmpty()) {
                    messages.add(HelmMessage.assistant(result.text()));
                    listener.onEvent(new EngineEvent.TurnSucceeded(turn, usage));
                    return new AgentEngineResult(result.text(), messages, new TokenUsage(inputTokens, outputTokens));
                }

                for (ModelStreamEvent.ToolCallRequested toolCall : result.toolCalls()) {
                    listener.onEvent(new EngineEvent.ToolStarted(turn, toolCall.name(), toolCall.id()));
                    try {
                        validateToolInput(request.tools(), toolCall);
                        messages.add(new HelmMessage(
                                Role.ASSISTANT,
                                List.of(new ToolCallBlock(toolCall.id(), toolCall.name(), toolCall.input()))));
                        Object output = request.toolExecutor().execute("engine", toolCall.name(), toolCall.input());
                        validateToolOutput(toolCall.name(), output);
                        messages.add(
                                new HelmMessage(Role.TOOL, List.of(new ToolResultBlock(toolCall.id(), output, false))));
                        listener.onEvent(new EngineEvent.ToolSucceeded(turn, toolCall.name(), toolCall.id()));
                    } catch (ToolExecutionException toolException) {
                        listener.onEvent(new EngineEvent.ToolFailed(
                                turn,
                                toolCall.name(),
                                toolCall.id(),
                                toolException.code(),
                                toolException.getMessage()));
                        throw toolException;
                    } catch (Exception exception) {
                        String message = exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage();
                        listener.onEvent(new EngineEvent.ToolFailed(
                                turn, toolCall.name(), toolCall.id(), "TOOL_EXECUTION_FAILED", message));
                        throw new ToolExecutionException(
                                "Tool execution failed",
                                Map.of("tool", toolCall.name(), "operationId", "engine", "message", message),
                                Map.of());
                    }
                }
                listener.onEvent(new EngineEvent.TurnSucceeded(turn, result.usage()));
            } catch (ToolExecutionException toolException) {
                // Tool failure already recorded as ToolFailed; do not also emit TurnFailed.
                throw toolException;
            } catch (EngineException engineException) {
                listener.onEvent(
                        new EngineEvent.TurnFailed(turn, engineException.code(), engineException.getMessage()));
                throw engineException;
            }
        }

        throw new MaxTurnsExceededException(
                "Agent loop exceeded max turns: " + request.maxTurns(),
                Map.of("maxTurns", request.maxTurns()),
                Map.of());
    }

    private static void validateToolInput(List<ToolDescriptor> tools, ModelStreamEvent.ToolCallRequested toolCall) {
        ToolDescriptor descriptor = tools.stream()
                .filter(t -> t.name().equals(toolCall.name()))
                .findFirst()
                .orElse(null);
        if (descriptor == null) {
            // Tool resolution is delegated to the executor (it may know tools not described here).
            return;
        }
        if (!matchesSchema(descriptor.inputSchema(), toolCall.input())) {
            throw ToolExecutionException.inputInvalid(toolCall.name(), "input does not match declared schema");
        }
    }

    private static void validateToolOutput(String toolName, Object output) {
        if (output == null) {
            throw ToolExecutionException.outputInvalid(toolName, "tool returned null");
        }
    }

    private static boolean matchesSchema(JsonSchema schema, Object input) {
        if (input == null) {
            return schema.nullable();
        }
        return switch (schema.type()) {
            case "string" -> input instanceof String;
            case "integer", "number" -> input instanceof Number;
            case "boolean" -> input instanceof Boolean;
            case "object" -> input instanceof Map || input.getClass().isRecord();
            case "array" -> input instanceof List;
            default -> true;
        };
    }
}
