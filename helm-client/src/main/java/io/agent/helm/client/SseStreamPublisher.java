package io.agent.helm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.PromptStreamEvent;
import java.util.concurrent.Flow;

/**
 * Bridges a {@code Flow.Publisher<String>} of SSE body lines to a {@code Flow.Publisher<PromptStreamEvent>}.
 * Subscription to the upstream line publisher is deferred until a downstream subscriber subscribes, so the first SSE
 * frame is never submitted before the caller is ready to receive it (unlike
 * {@link java.util.concurrent.SubmissionPublisher}, which drops items published with no subscribers).
 *
 * <p>Lines are accumulated into frames (blank line = frame boundary) and each completed frame is parsed and forwarded
 * to the downstream subscriber as soon as it arrives, enabling true incremental delivery. Malformed frames cause the
 * downstream to receive {@code onError} with an {@link IllegalStateException}.
 */
final class SseStreamPublisher implements Flow.Publisher<PromptStreamEvent> {

    private final Flow.Publisher<String> lines;
    private final ObjectMapper mapper;

    SseStreamPublisher(Flow.Publisher<String> lines, ObjectMapper mapper) {
        this.lines = lines;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super PromptStreamEvent> subscriber) {
        lines.subscribe(new Bridge(mapper, subscriber));
    }

    private static final class Bridge implements Flow.Subscriber<String> {
        private final SseParser parser;
        private final Flow.Subscriber<? super PromptStreamEvent> downstream;
        private Flow.Subscription upstream;
        private final StringBuilder frame = new StringBuilder();
        private boolean hasData;

        Bridge(ObjectMapper mapper, Flow.Subscriber<? super PromptStreamEvent> downstream) {
            this.parser = new SseParser(mapper);
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.upstream = subscription;
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // Pull all upstream data; a single line chunk can produce many frames, so fine-grained demand
                    // can't be honored. The downstream subscriber controls flow via its own processing speed.
                    upstream.request(Long.MAX_VALUE);
                }

                @Override
                public void cancel() {
                    upstream.cancel();
                }
            });
        }

        @Override
        public void onNext(String line) {
            if (line == null) {
                return;
            }
            if (line.isEmpty()) {
                if (hasData) {
                    emit(frame.toString());
                    frame.setLength(0);
                    hasData = false;
                }
                return;
            }
            if (line.startsWith(":")) {
                // comment — ignored
                return;
            }
            if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) {
                    payload = payload.substring(1);
                }
                if (frame.length() > 0) {
                    frame.append('\n');
                }
                frame.append(payload);
                hasData = true;
                return;
            }
            // event:/id:/retry:/unknown fields are ignored
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (hasData) {
                emit(frame.toString());
                frame.setLength(0);
                hasData = false;
            }
            downstream.onComplete();
        }

        private void emit(String frameText) {
            String trimmed = frameText.trim();
            if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                return;
            }
            PromptStreamEvent event = parser.parseFrame(trimmed);
            if (event != null) {
                downstream.onNext(event);
            } else {
                downstream.onError(new IllegalStateException("malformed SSE frame, cannot parse: " + trimmed));
            }
        }
    }
}
