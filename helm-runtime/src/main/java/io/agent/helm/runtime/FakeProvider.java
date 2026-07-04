package io.agent.helm.runtime;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

public final class FakeProvider implements ModelProvider {
    private final String providerId;
    private final Queue<Script> scripts = new ConcurrentLinkedQueue<>();

    public FakeProvider(String providerId) {
        this.providerId = providerId;
    }

    public void enqueue(ModelStreamEvent... events) {
        scripts.add(Script.of(Arrays.copyOf(events, events.length)));
    }

    /** Make the next {@link #stream} call surface {@code error} via {@code onError}. */
    public void failWith(RuntimeException error) {
        scripts.add(Script.error(error));
    }

    @Override
    public boolean supports(ModelRef model) {
        return providerId.equals(model.providerId());
    }

    @Override
    public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
        Script script = scripts.poll();
        return subscriber -> {
            if (script != null && script.isError()) {
                subscriber.onSubscribe(NoopSubscription.INSTANCE);
                subscriber.onError(script.error());
                return;
            }
            ModelStreamEvent[] events = script == null ? new ModelStreamEvent[0] : script.events();
            subscriber.onSubscribe(new ScriptSubscription(subscriber, events));
        };
    }

    private enum NoopSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
    }

    private record Script(ModelStreamEvent[] events, RuntimeException error) {
        static Script of(ModelStreamEvent[] events) {
            return new Script(events, null);
        }

        static Script error(RuntimeException error) {
            return new Script(null, error);
        }

        boolean isError() {
            return error != null;
        }
    }

    private static final class ScriptSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super ModelStreamEvent> subscriber;
        private final ModelStreamEvent[] script;
        private int index;
        private long demand;
        private boolean draining;
        private boolean completed;
        private boolean cancelled;

        private ScriptSubscription(Flow.Subscriber<? super ModelStreamEvent> subscriber, ModelStreamEvent[] script) {
            this.subscriber = subscriber;
            this.script = script;
        }

        @Override
        public synchronized void request(long n) {
            if (cancelled || completed || n <= 0) {
                return;
            }
            demand = Math.addExact(demand, n);
            if (draining) {
                return;
            }
            draining = true;
            try {
                while (!cancelled && demand > 0 && index < script.length) {
                    demand--;
                    subscriber.onNext(script[index++]);
                }
                if (!cancelled && !completed && index >= script.length) {
                    completed = true;
                    subscriber.onComplete();
                }
            } catch (Throwable throwable) {
                cancelled = true;
                subscriber.onError(throwable);
            } finally {
                draining = false;
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
        }
    }
}
