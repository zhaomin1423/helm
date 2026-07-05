package io.agent.helm.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class LoggingRuntimeObserverTest {
    private final LoggingRuntimeObserver observer = new LoggingRuntimeObserver();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingRuntimeObserver.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void lifecycleEventLoggedAtInfo() {
        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op_1", null, 1));

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("operation.started").contains("op_1");
        });
    }

    @Test
    void toolEventLoggedAtDebug() {
        observer.onEvent(event(RuntimeEventType.TOOL_STARTED, "op_1", null, 3));

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains("tool.started");
        });
    }

    @Test
    void errorEventLoggedAtWarn() {
        observer.onEvent(event(RuntimeEventType.OPERATION_FAILED, "op_1", null, 2));

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("operation.failed");
        });
    }

    @Test
    void payloadIsNeverLoggedSoSecretsDoNotLeak() {
        observer.onEvent(
                event(RuntimeEventType.OPERATION_STARTED, "op_1", null, 1, Map.of("apiKey", "super-secret-value")));

        List<String> messages =
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(line -> line.contains("super-secret-value"));
        assertThat(messages).noneMatch(line -> line.contains("apiKey"));
    }

    private static RuntimeEventRecord event(
            RuntimeEventType type, String operationId, String workflowRunId, long sequence) {
        return event(type, operationId, workflowRunId, sequence, Map.of());
    }

    private static RuntimeEventRecord event(
            RuntimeEventType type,
            String operationId,
            String workflowRunId,
            long sequence,
            Map<String, Object> payload) {
        return new RuntimeEventRecord(
                "evt_" + sequence, operationId, workflowRunId, sequence, type, payload, Instant.now());
    }
}
