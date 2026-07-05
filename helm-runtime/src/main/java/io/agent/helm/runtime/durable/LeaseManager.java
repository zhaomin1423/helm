package io.agent.helm.runtime.durable;

import io.agent.helm.core.store.WorkQueue;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scans a {@link WorkQueue} for expired leases, requeues them, and invokes a {@link RecoveryHandler} so the runtime can
 * re-dispatch the operation. Post-GA durable scale component; the default synchronous runtime does not start a lease
 * manager — applications opt in via {@code AgentRuntime.Builder.durable(executor, workQueue)}.
 *
 * <p>Recovery happens BEFORE requeue within each scan iteration, wrapped in try/catch so one failed recovery does not
 * block the rest. The scan task is {@link AtomicReference}-guarded so {@link #start}/{@link #stop} are safe under
 * concurrent access.
 */
public final class LeaseManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LeaseManager.class.getName());

    private final WorkQueue workQueue;
    private final ScheduledExecutorService scheduler;
    private final Duration scanInterval;
    private final RecoveryHandler recoveryHandler;
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    public LeaseManager(
            WorkQueue workQueue,
            ScheduledExecutorService scheduler,
            Duration scanInterval,
            RecoveryHandler recoveryHandler) {
        this.workQueue = workQueue;
        this.scheduler = scheduler;
        this.scanInterval = scanInterval;
        this.recoveryHandler = recoveryHandler;
    }

    /** Starts periodic scanning. */
    public void start() {
        ScheduledFuture<?> existing = task.getAndSet(scheduler.scheduleAtFixedRate(
                this::scan, scanInterval.toMillis(), scanInterval.toMillis(), TimeUnit.MILLISECONDS));
        if (existing != null) {
            existing.cancel(false);
            throw new IllegalStateException("LeaseManager already started");
        }
    }

    /** Stops periodic scanning. */
    public void stop() {
        ScheduledFuture<?> current = task.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }
    }

    /** Stops scanning and shuts down the internal scheduler. Idempotent. */
    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * Scans once for expired leases. For each expired item, the recovery handler is invoked FIRST; if recovery throws,
     * the exception is logged (the item is still requeued so another worker can pick it up). Wrapped per-iteration so
     * one failure does not abort the whole scan.
     */
    public void scan() {
        List<WorkQueue.QueueItem> expired = workQueue.expiredLeases();
        for (WorkQueue.QueueItem item : expired) {
            try {
                recoveryHandler.recover(item.operationId());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Recovery failed for operation {0}: {1}", new Object[] {
                    item.operationId(), e.getMessage()
                });
            }
            try {
                workQueue.requeue(item.leaseId());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Requeue failed for operation {0}: {1}", new Object[] {
                    item.operationId(), e.getMessage()
                });
            }
        }
    }

    @FunctionalInterface
    public interface RecoveryHandler {
        void recover(String operationId);
    }
}
