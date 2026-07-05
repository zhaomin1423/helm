package io.agent.helm.core.store;

import io.agent.helm.core.annotation.Preview;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable work queue for queue-backed admission, claim/lease/renew/recovery. SPI anchor for the post-GA durable scale
 * runtime (design doc {@code docs/design/09-durable-scale-runtime.md}); not yet wired into {@link AgentRuntime} — the
 * default runtime remains synchronous. {@code @Preview} M11 post-GA; the SPI shape is being validated and may change
 * before implementation.
 */
@Preview
public interface WorkQueue {

    /** Enqueues an operation for a session, returning a monotonic sequence number. */
    long enqueue(String operationId, String sessionId);

    /** Claims the oldest queued item for a worker, granting a lease that expires at {@code now + leaseTtl}. */
    Optional<QueueItem> claim(String workerId, Duration leaseTtl);

    /**
     * Claims a specific queued operation for a worker, granting a lease that expires at {@code now + leaseTtl}. Use
     * this when a worker has a specific {@code operationId} to process (e.g. after durable dispatch) and must not grab
     * an unrelated item. The default implementation falls back to {@link #claim(String, Duration)} and releases the
     * claim when the oldest item does not match; durable-aware implementations should override with an atomic
     * find-and-claim.
     */
    default Optional<QueueItem> claim(String operationId, String workerId, Duration leaseTtl) {
        Optional<QueueItem> claimed = claim(workerId, leaseTtl);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        QueueItem item = claimed.get();
        if (item.operationId().equals(operationId)) {
            return claimed;
        }
        // Wrong item claimed — release it back so another worker can process it.
        requeue(item.leaseId());
        return Optional.empty();
    }

    /** Renews an in-flight lease; returns {@code false} if the lease was already reclaimed. */
    boolean renew(String leaseId, Duration ttl);

    /**
     * Completes the lease, marking the operation with a terminal status.
     *
     * @param terminalStatus a terminal status; {@link OperationStatus#isTerminal()} must return {@code true}.
     * @throws IllegalArgumentException if {@code terminalStatus} is not terminal.
     */
    void complete(String leaseId, OperationStatus terminalStatus);

    /** Returns the lease to the queue for another worker to claim. */
    void requeue(String leaseId);

    /** Lists leases whose TTL has expired (for {@code RecoveryService} to requeue). */
    List<QueueItem> expiredLeases();

    /** A queued operation with its current lease state. */
    record QueueItem(long sequence, String operationId, String sessionId, String leaseId, Instant leaseExpiresAt) {}
}
