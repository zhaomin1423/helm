package io.agent.helm.engine;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class TurnRunner {
    TurnResult run(ModelProvider provider, ModelRequest request) {
        StringBuilder text = new StringBuilder();
        List<ModelStreamEvent.ToolCallRequested> toolCalls = new ArrayList<>();
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
                throw new IllegalStateException("Model stream timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(subscriptionRef);
            throw new IllegalStateException("Interrupted while waiting for model stream", e);
        }

        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Model stream failed", throwable);
        }

        return new TurnResult(text.toString(), List.copyOf(toolCalls));
    }

    private static void cancel(AtomicReference<Flow.Subscription> subscription) {
        Flow.Subscription current = subscription.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }
}
