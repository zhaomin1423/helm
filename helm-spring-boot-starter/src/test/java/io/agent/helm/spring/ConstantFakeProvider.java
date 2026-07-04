package io.agent.helm.spring;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.concurrent.Flow;

/** Test provider that always streams a fixed terminal response, regardless of input. */
public final class ConstantFakeProvider implements ModelProvider {
    private final String providerId;

    public ConstantFakeProvider(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public boolean supports(ModelRef model) {
        return providerId.equals(model.providerId());
    }

    @Override
    public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public synchronized void request(long n) {
                if (done || n <= 0) {
                    return;
                }
                done = true;
                subscriber.onNext(new ModelStreamEvent.ContentDelta("ok"));
                subscriber.onNext(new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }
}
