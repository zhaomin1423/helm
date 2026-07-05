package io.agent.helm.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract test pinning the {@link RuntimeEventObserver} SPI obligations. The actual catch-and-log path lives in
 * helm-runtime; this test asserts the contract surface and the safe-dispatch pattern the runtime must follow when an
 * observer throws.
 */
final class RuntimeEventObserverContractTest {

    @Test
    void observerIsFunctionalInterfaceWithSingleAbstractMethod() {
        assertThat(RuntimeEventObserver.class.getAnnotation(FunctionalInterface.class))
                .as("RuntimeEventObserver should be a @FunctionalInterface so adapters can use lambdas")
                .isNotNull();
        assertThat(RuntimeEventObserver.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    void safeDispatchCatchesThrowingObserverWithoutPropagating() {
        RecordingSink sink = new RecordingSink();
        RuntimeEventRecord event = new RuntimeEventRecord(
                "e1", "op1", null, 1L, RuntimeEventType.OPERATION_STARTED, Map.of(), Instant.EPOCH);

        // The throwing observer must not cause safeDispatch to propagate; the exception is recorded for logging.
        assertThatCode(() -> safeDispatch(throwingObserver(), event, sink)).doesNotThrowAnyException();
        assertThat(sink.lastError).isNotNull().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void safeDispatchDeliversToNonThrowingObserver() {
        RecordingSink sink = new RecordingSink();
        RuntimeEventRecord event = new RuntimeEventRecord(
                "e2", "op1", null, 2L, RuntimeEventType.OPERATION_SUCCEEDED, Map.of(), Instant.EPOCH);

        safeDispatch(sink, event, sink);

        assertThat(sink.received).isSameAs(event);
        assertThat(sink.lastError).isNull();
    }

    @Test
    void safeDispatchContinuesToNextObserverAfterOneThrows() {
        RecordingSink sink = new RecordingSink();
        RuntimeEventRecord event = new RuntimeEventRecord(
                "e3", "op1", null, 3L, RuntimeEventType.OPERATION_FAILED, Map.of(), Instant.EPOCH);

        // The runtime dispatches to observers in order; a throwing observer must not prevent later observers from
        // receiving the event.
        safeDispatch(throwingObserver(), event, sink);
        safeDispatch(sink, event, sink);

        assertThat(sink.lastError).isNotNull();
        assertThat(sink.received).isSameAs(event);
    }

    /** Reference implementation of the runtime's safe-dispatch path: invoke, catch Throwable, log to sink. */
    static void safeDispatch(RuntimeEventObserver observer, RuntimeEventRecord event, RecordingSink sink) {
        try {
            observer.onEvent(event);
        } catch (Throwable t) {
            sink.lastError = t;
        }
    }

    static RuntimeEventObserver throwingObserver() {
        return event -> {
            throw new IllegalStateException("observer bug");
        };
    }

    static final class RecordingSink implements RuntimeEventObserver {
        volatile RuntimeEventRecord received;
        volatile Throwable lastError;

        @Override
        public void onEvent(RuntimeEventRecord event) {
            this.received = event;
        }
    }
}
