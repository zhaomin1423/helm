package io.agent.helm.client;

import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.error.HelmException;
import java.util.concurrent.Flow;

/**
 * Streams an error response body into a {@link HelmException} and signals it via {@code onError} to the downstream
 * subscriber. Used by {@link DefaultHelmClient#promptStream} when the HTTP status is >= 400. The body lines are
 * collected (error bodies are small) and then mapped to the matching {@link HelmException}.
 */
final class ErrorStreamPublisher implements Flow.Publisher<PromptStreamEvent> {

    private final StreamResponse response;
    private final ClientErrorMapper errors;

    ErrorStreamPublisher(StreamResponse response, ClientErrorMapper errors) {
        this.response = response;
        this.errors = errors;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super PromptStreamEvent> subscriber) {
        Collector collector = new Collector(response, errors, subscriber);
        response.lines().subscribe(collector);
    }

    private static final class Collector implements Flow.Subscriber<String> {
        private final StreamResponse response;
        private final ClientErrorMapper errors;
        private final Flow.Subscriber<? super PromptStreamEvent> downstream;
        private final StringBuilder body = new StringBuilder();

        Collector(
                StreamResponse response,
                ClientErrorMapper errors,
                Flow.Subscriber<? super PromptStreamEvent> downstream) {
            this.response = response;
            this.errors = errors;
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void cancel() {
                    subscription.cancel();
                }
            });
        }

        @Override
        public void onNext(String line) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(new RuntimeException("failed to read streaming error response", throwable));
        }

        @Override
        public void onComplete() {
            try {
                RawResponse errorResponse = new RawResponse(response.status(), body.toString(), response.headers());
                downstream.onError(errors.toException(errorResponse));
            } catch (Exception e) {
                downstream.onError(e);
            }
        }
    }
}
