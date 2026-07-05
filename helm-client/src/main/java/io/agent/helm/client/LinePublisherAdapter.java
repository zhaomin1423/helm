package io.agent.helm.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Transforms a {@code Flow.Publisher<List<ByteBuffer>>} (raw HTTP body chunks from
 * {@link java.net.http.HttpResponse.BodyHandlers#ofPublisher()}) into a {@code Flow.Publisher<String>} of lines,
 * stripping {@code \r\n}/{@code \n} terminators and emitting empty strings for blank lines (which SSE uses as frame
 * boundaries).
 *
 * <p>Backpressure is simplified: any downstream request pulls all upstream chunks. This is appropriate for streaming
 * SSE where lines must be consumed in order and a single upstream chunk can produce many lines.
 */
final class LinePublisherAdapter implements Flow.Publisher<String> {

    private final Flow.Publisher<List<ByteBuffer>> upstream;

    LinePublisherAdapter(Flow.Publisher<List<ByteBuffer>> upstream) {
        this.upstream = upstream;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super String> subscriber) {
        upstream.subscribe(new LineTranslator(subscriber));
    }

    private static final class LineTranslator implements Flow.Subscriber<List<ByteBuffer>> {
        private final Flow.Subscriber<? super String> downstream;
        private Flow.Subscription upstream;
        private final StringBuilder pending = new StringBuilder();

        LineTranslator(Flow.Subscriber<? super String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.upstream = subscription;
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // Pull all upstream data; a single chunk can produce many lines, so we can't honor fine-grained
                    // requests. The SubmissionPublisher on the output side provides buffering/backpressure.
                    upstream.request(Long.MAX_VALUE);
                }

                @Override
                public void cancel() {
                    upstream.cancel();
                }
            });
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                String chunk = new String(bytes, StandardCharsets.UTF_8);
                for (int i = 0; i < chunk.length(); i++) {
                    char c = chunk.charAt(i);
                    if (c == '\n') {
                        String line = pending.toString();
                        if (line.endsWith("\r")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        downstream.onNext(line);
                        pending.setLength(0);
                    } else {
                        pending.append(c);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (pending.length() > 0) {
                String line = pending.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                downstream.onNext(line);
                pending.setLength(0);
            }
            downstream.onComplete();
        }
    }
}
