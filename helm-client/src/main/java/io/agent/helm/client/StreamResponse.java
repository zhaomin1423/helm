package io.agent.helm.client;

import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Streaming HTTP response: status, headers, and a {@link Flow.Publisher} of body lines. Used by
 * {@link HttpTransport#sendStream} so callers can consume SSE frames incrementally as they arrive rather than buffering
 * the entire body.
 *
 * @param status HTTP status code.
 * @param headers first value of each response header.
 * @param lines publisher of response body lines (line terminators stripped); empty body yields a publisher that
 *     completes immediately.
 */
record StreamResponse(int status, Map<String, String> headers, Flow.Publisher<String> lines) {

    StreamResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        lines = lines == null ? new EmptyPublisher<>() : lines;
    }

    /** Publisher that completes immediately without emitting any items. */
    private static final class EmptyPublisher<T> implements Flow.Publisher<T> {
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {}
            });
            subscriber.onComplete();
        }
    }
}
