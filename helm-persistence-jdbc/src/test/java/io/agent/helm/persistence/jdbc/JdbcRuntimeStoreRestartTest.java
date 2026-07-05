package io.agent.helm.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.core.error.SessionConflictException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.RuntimeStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies session/operation/workflow-run/event history survives a process "restart" (file-mode H2). */
class JdbcRuntimeStoreRestartTest {

    @Test
    void operationAndEventsSurviveReconnect(@TempDir Path tempDir) {
        String url = "jdbc:h2:file:" + tempDir.resolve("helm").toAbsolutePath();

        JdbcConnectionPool first = JdbcConnectionPool.create(url, "sa", "");
        HelmSchema.migrate(first);
        RuntimeStore storeOne = new JdbcRuntimeStore(first);
        storeOne.saveOperation(new OperationRecord(
                "op_restart",
                "s1",
                HelmAction.PROMPT,
                OperationStatus.SUCCEEDED,
                "in",
                "out",
                Map.of(),
                Instant.ofEpochSecond(1000),
                Instant.ofEpochSecond(2000)));
        storeOne.appendEvent(new RuntimeEventRecord(
                "e1",
                "op_restart",
                null,
                1,
                RuntimeEventType.OPERATION_STARTED,
                Map.of(),
                Instant.ofEpochSecond(1000)));
        storeOne.appendEvent(new RuntimeEventRecord(
                "e2",
                "op_restart",
                null,
                2,
                RuntimeEventType.OPERATION_SUCCEEDED,
                Map.of("turnCount", 1),
                Instant.ofEpochSecond(2000)));
        first.dispose();

        JdbcConnectionPool second = JdbcConnectionPool.create(url, "sa", "");
        HelmSchema.migrate(second);
        RuntimeStore storeTwo = new JdbcRuntimeStore(second);
        try {
            assertThat(storeTwo.loadOperation("op_restart")).isPresent().hasValueSatisfying(op -> {
                assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
                assertThat(op.output()).isEqualTo("out");
                assertThat(op.type()).isEqualTo(HelmAction.PROMPT);
            });
            // Events persisted before dispose survive the reconnect, ordered by sequence.
            assertThat(storeTwo.eventsForOperation("op_restart"))
                    .extracting(RuntimeEventRecord::id)
                    .containsExactly("e1", "e2");
        } finally {
            second.dispose();
        }
    }

    /**
     * Two concurrent saves targeting the same session id: the OCC compare-and-swap must let exactly one win and force
     * the other to throw {@link SessionConflictException}.
     */
    @Test
    void concurrentSavesOnSameSessionOneConflicts() throws InterruptedException {
        JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:helm-occ;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            HelmSchema.migrate(pool);
            RuntimeStore store = new JdbcRuntimeStore(pool);

            // Seed the session at version 1.
            store.saveSession(new AgentSessionState(
                    "s-occ",
                    "agent",
                    "inst",
                    "default",
                    1,
                    List.of(),
                    Instant.ofEpochSecond(1),
                    Instant.ofEpochSecond(1)));

            int threads = 8;
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger conflicts = new AtomicInteger();
            AtomicInteger successes = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Each thread believes the session is still at version 1 and tries to save at that version.
                    try {
                        store.saveSession(new AgentSessionState(
                                "s-occ",
                                "agent",
                                "inst",
                                "default",
                                1,
                                List.of(),
                                Instant.ofEpochSecond(1),
                                Instant.ofEpochSecond(2)));
                        successes.incrementAndGet();
                    } catch (SessionConflictException e) {
                        conflicts.incrementAndGet();
                    }
                    return;
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            fire.countDown();
            exec.shutdown();
            assertThat(exec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Exactly one save wins the compare-and-swap; all others see the bumped version and conflict.
            assertThat(successes.get()).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(threads - 1);
        } finally {
            pool.dispose();
        }
    }

    /** Replaying the same event (same id / sequence) is idempotent: no error, no duplicate row. */
    @Test
    void appendEventIsIdempotentOnReplay() {
        JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:helm-idem;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            HelmSchema.migrate(pool);
            RuntimeStore store = new JdbcRuntimeStore(pool);
            RuntimeEventRecord event = new RuntimeEventRecord(
                    "e1", "op-1", null, 1, RuntimeEventType.OPERATION_STARTED, Map.of(), Instant.ofEpochSecond(1));

            store.appendEvent(event);
            // Replay the exact same event — must not throw.
            store.appendEvent(event);

            assertThat(store.eventsForOperation("op-1"))
                    .extracting(RuntimeEventRecord::id)
                    .containsExactly("e1");
        } finally {
            pool.dispose();
        }
    }

    /** Deleting a session cascades to its operations and events. */
    @Test
    void deleteSessionCascadesToOperationsAndEvents() {
        JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:helm-cascade;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            HelmSchema.migrate(pool);
            RuntimeStore store = new JdbcRuntimeStore(pool);
            store.saveSession(new AgentSessionState(
                    "s-cascade",
                    "agent",
                    "inst",
                    "default",
                    1,
                    List.of(),
                    Instant.ofEpochSecond(1),
                    Instant.ofEpochSecond(1)));
            store.saveOperation(new OperationRecord(
                    "op-cascade",
                    "s-cascade",
                    HelmAction.PROMPT,
                    OperationStatus.SUCCEEDED,
                    "in",
                    "out",
                    Map.of(),
                    Instant.ofEpochSecond(1),
                    Instant.ofEpochSecond(2)));
            store.appendEvent(new RuntimeEventRecord(
                    "e-cascade",
                    "op-cascade",
                    null,
                    1,
                    RuntimeEventType.OPERATION_SUCCEEDED,
                    Map.of(),
                    Instant.ofEpochSecond(2)));

            store.deleteSession("s-cascade");

            assertThat(store.loadOperation("op-cascade")).isEmpty();
            assertThat(store.eventsForOperation("op-cascade")).isEmpty();
        } finally {
            pool.dispose();
        }
    }

    /**
     * {@code toHelmException} attaches the {@link SQLException} as cause, sets a {@code retryable} flag in details
     * derived from the SQLSTATE class, and surfaces {@code sqlState} when present. Triggered here by inserting a
     * session whose id exceeds {@code VARCHAR(255)} — a data exception (22xxx, not retryable).
     */
    @Test
    void toHelmExceptionCarriesCauseAndRetryableFlag() {
        JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:helm-err;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            HelmSchema.migrate(pool);
            RuntimeStore store = new JdbcRuntimeStore(pool);
            String tooLongId = "x".repeat(300); // exceeds helm_session.id VARCHAR(255)
            assertThatThrownBy(() -> store.saveSession(new AgentSessionState(
                            tooLongId,
                            "agent",
                            "inst",
                            "default",
                            1,
                            List.of(),
                            Instant.ofEpochSecond(1),
                            Instant.ofEpochSecond(1))))
                    .isInstanceOf(PersistenceException.class)
                    .satisfies(ex -> {
                        PersistenceException pe = (PersistenceException) ex;
                        // The original SQLException is preserved as cause.
                        assertThat(pe.getCause()).isInstanceOf(SQLException.class);
                        // Data exceptions (22xxx) are not retryable.
                        assertThat(pe.details()).containsEntry("retryable", false);
                        // sqlState is surfaced in details when the driver supplied one (no "null" string).
                        assertThat(pe.details()).containsKey("sqlState");
                    });
        } finally {
            pool.dispose();
        }
    }
}
