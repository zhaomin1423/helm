package io.agent.helm.engine;

import io.agent.helm.core.error.EngineInterruptedException;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ModelStreamException;
import io.agent.helm.core.error.TurnTimeoutException;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a single model turn: subscribes to the provider stream, accumulates text and tool calls, captures token usage.
 */
final class TurnRunner {

    TurnResult run(ModelProvider provider, ModelRequest request) {
        StringBuilder text = new StringBuilder();
        List<ModelStreamEvent.ToolCallRequested> toolCalls = new ArrayList<>();
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>(new TokenUsage(0, 0));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

        provider.stream(request).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(1);
            }

            @Override
            public void onNext(ModelStreamEvent event) {
                Flow.Subscription current = subscriptionRef.get();
                if (current == null) {
                    return;
                }
                if (event instanceof ModelStreamEvent.ContentDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof ModelStreamEvent.ToolCallRequested toolCall) {
                    toolCalls.add(toolCall);
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
                cancel(subscriptionRef);
                throw new TurnTimeoutException(
                        "Model stream timed out after " + request.timeout(),
                        Map.of("timeout", String.valueOf(request.timeout())),
                        Map.of());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(subscriptionRef);
            throw new EngineInterruptedException("Interrupted while waiting for model stream", Map.of(), Map.of());
        }

        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof HelmException helmException) {
                throw helmException;
            }
            String reason =
                    throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            throw new ModelStreamException("Model stream failed: " + reason, Map.of(), Map.of("cause", reason));
        }

        return new TurnResult(text.toString(), toolCalls, usageRef.get());
    }

    private static void cancel(AtomicReference<Flow.Subscription> subscription) {
        Flow.Subscription current = subscription.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }
}
