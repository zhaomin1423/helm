package io.agent.helm.engine;

import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.error.EngineException;
import io.agent.helm.core.error.EngineInterruptedException;
import io.agent.helm.core.error.MaxTurnsExceededException;
import io.agent.helm.core.error.ModelStreamException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.error.TurnTimeoutException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Streams prompt events to a subscriber. Each model {@code ContentDelta} is forwarded as it arrives; tool calls
     * emit {@link PromptStreamEvent.ToolCallRequested}/{@link PromptStreamEvent.ToolResultReady} and the loop
     * continues. The terminal {@link PromptStreamEvent.OperationCompleted}/{@link PromptStreamEvent.OperationFailed}
     * are the runtime's responsibility — the engine only closes the publisher (exceptionally on failure). @Preview
     * incremental streaming surface; persistence of streamed sessions is the runtime's concern.
     */
    @io.agent.helm.core.annotation.Preview
    public Flow.Publisher<PromptStreamEvent> runStream(
            AgentEngineRequest request, java.util.function.BiConsumer<List<HelmMessage>, TokenUsage> onComplete) {
        return subscriber -> {
            SubmissionPublisher<PromptStreamEvent> pub = new SubmissionPublisher<>();
            pub.subscribe(subscriber);
            try {
                runStreamLoop(request, pub, onComplete);
                pub.close();
            } catch (RuntimeException e) {
                pub.closeExceptionally(e);
            }
        };
    }

    /** Convenience overload without a completion callback. */
    public Flow.Publisher<PromptStreamEvent> runStream(AgentEngineRequest request) {
        return runStream(request, (messages, usage) -> {});
    }

    private void runStreamLoop(
            AgentEngineRequest request,
            SubmissionPublisher<PromptStreamEvent> pub,
            java.util.function.BiConsumer<List<HelmMessage>, TokenUsage> onComplete) {
        EngineEventListener listener = request.listener();
        List<HelmMessage> messages = new ArrayList<>(request.messages());
        long inputTokens = 0;
        long outputTokens = 0;
        for (int turn = 0; turn < request.maxTurns(); turn++) {
            listener.onEvent(new EngineEvent.TurnStarted(turn));
            listener.onEvent(new EngineEvent.ModelStarted(turn));
            StringBuilder text = new StringBuilder();
            List<ModelStreamEvent.ToolCallRequested> toolCalls = new ArrayList<>();
            AtomicReference<TokenUsage> usageRef = new AtomicReference<>(new TokenUsage(0, 0));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();
            request.provider().stream(new ModelRequest(
                            request.model(), request.instructions(), request.tools(), messages, request.timeout()))
                    .subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            subRef.set(subscription);
                            subscription.request(1);
                        }

                        @Override
                        public void onNext(ModelStreamEvent event) {
                            Flow.Subscription current = subRef.get();
                            if (current == null) {
                                return;
                            }
                            if (event instanceof ModelStreamEvent.ContentDelta delta) {
                                text.append(delta.text());
                                pub.submit(new PromptStreamEvent.ContentDelta(delta.text()));
                            } else if (event instanceof ModelStreamEvent.ToolCallRequested toolCall) {
                                toolCalls.add(toolCall);
                                pub.submit(new PromptStreamEvent.ToolCallRequested(
                                        toolCall.id(), toolCall.name(), toolCall.input()));
                            } else if (event instanceof ModelStreamEvent.Completed completed) {
                                usageRef.set(completed.usage());
                            }
                            current.request(1);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            done.countDown();
                        }

                        @Override
                        public void onComplete() {
                            done.countDown();
                        }
                    });
            try {
                if (!done.await(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    cancelSubscription(subRef);
                    throw new TurnTimeoutException(
                            "Model stream timed out after " + request.timeout(),
                            Map.of("timeout", String.valueOf(request.timeout())),
                            Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSubscription(subRef);
                throw new EngineInterruptedException("Interrupted while waiting for model stream", Map.of(), Map.of());
            }
            Throwable throwable = failure.get();
            if (throwable != null) {
                if (throwable instanceof io.agent.helm.core.error.HelmException helmException) {
                    throw helmException;
                }
                String reason =
                        throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                throw new ModelStreamException("Model stream failed: " + reason, Map.of(), Map.of("cause", reason));
            }
            TokenUsage usage = usageRef.get();
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            listener.onEvent(new EngineEvent.ModelSucceeded(turn, usage));
            if (toolCalls.isEmpty()) {
                messages.add(HelmMessage.assistant(text.toString()));
                listener.onEvent(new EngineEvent.TurnSucceeded(turn, usage));
                pub.submit(new PromptStreamEvent.TurnEnded(turn, usage));
                onComplete.accept(messages, new TokenUsage(inputTokens, outputTokens));
                return;
            }
            for (ModelStreamEvent.ToolCallRequested toolCall : toolCalls) {
                listener.onEvent(new EngineEvent.ToolStarted(turn, toolCall.name(), toolCall.id()));
                validateToolInput(request.tools(), toolCall);
                messages.add(new HelmMessage(
                        Role.ASSISTANT, List.of(new ToolCallBlock(toolCall.id(), toolCall.name(), toolCall.input()))));
                Object output = request.toolExecutor().execute("engine", toolCall.name(), toolCall.input());
                validateToolOutput(toolCall.name(), output);
                messages.add(new HelmMessage(Role.TOOL, List.of(new ToolResultBlock(toolCall.id(), output, false))));
                pub.submit(new PromptStreamEvent.ToolResultReady(toolCall.id(), toolCall.name(), output));
                listener.onEvent(new EngineEvent.ToolSucceeded(turn, toolCall.name(), toolCall.id()));
            }
            listener.onEvent(new EngineEvent.TurnSucceeded(turn, usage));
            pub.submit(new PromptStreamEvent.TurnEnded(turn, usage));
        }
        throw new MaxTurnsExceededException(
                "Agent loop exceeded max turns: " + request.maxTurns(),
                Map.of("maxTurns", request.maxTurns()),
                Map.of());
    }

    private static void cancelSubscription(AtomicReference<Flow.Subscription> subscription) {
        Flow.Subscription current = subscription.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
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
