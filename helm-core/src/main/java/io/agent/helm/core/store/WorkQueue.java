package io.agent.helm.core.store;

import io.agent.helm.core.annotation.Preview;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable work queue for queue-backed admission, claim/lease/renew/recovery. SPI anchor for the post-GA durable scale
 * runtime (design doc {@code docs/design/09-durable-scale-runtime.md}); not yet wired into {@link AgentRuntime} — the
 * default runtime remains synchronous. @Preview M11 post-GA; the SPI shape is being validated and may change before
 * implementation.
 */
@Preview
public interface WorkQueue {

    /** Enqueues an operation for a session, returning a monotonic sequence number. */
    long enqueue(String operationId, String sessionId);

    /** Claims the oldest queued item for a worker, granting a lease that expires at {@code now + leaseTtl}. */
    Optional<QueueItem> claim(String workerId, Duration leaseTtl);

    /** Renews an in-flight lease; returns {@code false} if the lease was already reclaimed. */
    boolean renew(String leaseId, Duration ttl);

    /** Completes the lease, marking the operation with a terminal status. */
    void complete(String leaseId, OperationStatus terminalStatus);

    /** Returns the lease to the queue for another worker to claim. */
    void requeue(String leaseId);

    /** Lists leases whose TTL has expired (for {@code RecoveryService} to requeue). */
    List<QueueItem> expiredLeases();

    /** A queued operation with its current lease state. */
    record QueueItem(long sequence, String operationId, String sessionId, String leaseId, Instant leaseExpiresAt) {}
}
