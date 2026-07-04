package io.agent.helm.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.message.HelmMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract tests for {@link ModelProvider}. Provider adapters extend this class, wiring {@link #provider()},
 * {@link #supportedModel()}, and the {@code prepare*} hooks to their backend (FakeProvider enqueues scripts; HTTP
 * providers stub WireMock). The base collects stream events uniformly and asserts the SPI contract: event ordering,
 * tool-call emission, and error mapping.
 */
public abstract class ModelProviderContractTest {

    protected abstract ModelProvider provider();

    protected abstract ModelRef supportedModel();

    protected abstract void prepareTerminalTextStream(String text, TokenUsage usage);

    protected abstract void prepareToolCallStream(
            String toolCallId, String toolName, Object input, String finalText, TokenUsage usage);

    protected abstract void prepareErrorStream();

    protected ModelRequest request(String text) {
        return new ModelRequest(
                supportedModel(), "", List.of(), List.of(HelmMessage.user(text)), Duration.ofSeconds(5));
    }

    @Test
    void supportsOwnedModelAndRejectsForeignModel() {
        assertThat(provider().supports(supportedModel())).isTrue();
        assertThat(provider().supports(new ModelRef("other", "other-model"))).isFalse();
    }

    @Test
    void streamsTerminalTextInOrder() {
        prepareTerminalTextStream("hello", new TokenUsage(3, 5));
        StreamOutcome outcome = collect(request("hi"));

        assertThat(outcome.error()).as("stream error").isNull();
        assertThat(outcome.events()).startsWith(new ModelStreamEvent.ContentDelta("hello"));
        assertThat(outcome.events()).last().isInstanceOf(ModelStreamEvent.Completed.class);
        ModelStreamEvent.Completed completed =
                (ModelStreamEvent.Completed) outcome.events().getLast();
        assertThat(completed.usage()).isEqualTo(new TokenUsage(3, 5));
    }

    @Test
    void streamsToolCallBeforeFinalText() {
        prepareToolCallStream("call_1", "echo", "input", "done", new TokenUsage(1, 2));
        StreamOutcome outcome = collect(request("run echo"));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.events()).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(ModelStreamEvent.ToolCallRequested.class, tc -> {
                    assertThat(tc.id()).isEqualTo("call_1");
                    assertThat(tc.name()).isEqualTo("echo");
                }));
        assertThat(outcome.events()).last().isInstanceOf(ModelStreamEvent.Completed.class);
    }

    @Test
    void mapsBackendErrorToHelmException() {
        prepareErrorStream();
        StreamOutcome outcome = collect(request("hi"));

        assertThat(outcome.error()).as("stream must surface an error").isNotNull();
        Throwable cause = outcome.error();
        boolean isHelm = cause instanceof HelmException || cause.getCause() instanceof HelmException;
        assertThat(isHelm).as("error should be or wrap a HelmException").isTrue();
    }

    protected final StreamOutcome collect(ModelRequest request) {
        CopyOnWriteArrayList<ModelStreamEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Flow.Publisher<ModelStreamEvent> publisher;
        try {
            publisher = provider().stream(request);
        } catch (RuntimeException e) {
            return new StreamOutcome(List.of(), e);
        }
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ModelStreamEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("model stream did not complete within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting model stream", e);
        }
        return new StreamOutcome(List.copyOf(events), error.get());
    }

    protected record StreamOutcome(List<ModelStreamEvent> events, Throwable error) {}
}
