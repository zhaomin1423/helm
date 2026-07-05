package io.agent.helm.runtime.durable;

import io.agent.helm.core.store.WorkQueue;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scans a {@link WorkQueue} for expired leases, requeues them, and invokes a {@link RecoveryHandler} so the runtime can
 * re-dispatch the operation. Post-GA durable scale component; the default synchronous runtime does not start a lease
 * manager — applications opt in via {@code AgentRuntime.Builder.durable(executor, workQueue)}.
 */
public final class LeaseManager {

    private final WorkQueue workQueue;
    private final ScheduledExecutorService scheduler;
    private final Duration scanInterval;
    private final RecoveryHandler recoveryHandler;
    private ScheduledFuture<?> task;

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
        if (task != null) {
            throw new IllegalStateException("LeaseManager already started");
        }
        task = scheduler.scheduleAtFixedRate(
                this::scan, scanInterval.toMillis(), scanInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops periodic scanning. */
    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    /** Scans once for expired leases, requeues them, and triggers recovery. Visible for testing. */
    public void scan() {
        List<WorkQueue.QueueItem> expired = workQueue.expiredLeases();
        for (WorkQueue.QueueItem item : expired) {
            workQueue.requeue(item.leaseId());
            recoveryHandler.recover(item.operationId());
        }
    }

    @FunctionalInterface
    public interface RecoveryHandler {
        void recover(String operationId);
    }
}
