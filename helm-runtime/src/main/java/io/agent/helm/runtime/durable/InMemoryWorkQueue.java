package io.agent.helm.runtime.durable;

import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.WorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link WorkQueue} for dev/test and single-process durable-mode prototyping. State is lost on process
 * restart — production durability requires a DB-backed implementation (see
 * {@code docs/design/09-durable-scale-runtime.md}).
 */
public final class InMemoryWorkQueue implements WorkQueue {

    private final ConcurrentMap<String, QueueItem> items = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> leaseToOperation = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OperationStatus> completedStatus = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public long enqueue(String operationId, String sessionId) {
        long seq = sequence.incrementAndGet();
        items.put(operationId, new QueueItem(seq, operationId, sessionId, null, null));
        return seq;
    }

    @Override
    public synchronized Optional<QueueItem> claim(String workerId, Duration leaseTtl) {
        Instant now = Instant.now();
        Instant expires = now.plus(leaseTtl);
        QueueItem oldest = findOldestClaimable(now);
        if (oldest == null) {
            return Optional.empty();
        }
        return Optional.of(claimItem(oldest, expires));
    }

    @Override
    public synchronized Optional<QueueItem> claim(String operationId, String workerId, Duration leaseTtl) {
        QueueItem item = items.get(operationId);
        if (item == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (item.leaseId() != null
                && item.leaseExpiresAt() != null
                && item.leaseExpiresAt().isAfter(now)) {
            // Already actively leased by another worker.
            return Optional.empty();
        }
        return Optional.of(claimItem(item, now.plus(leaseTtl)));
    }

    private QueueItem claimItem(QueueItem item, Instant expires) {
        String leaseId = "lease_" + UUID.randomUUID();
        QueueItem claimed = new QueueItem(item.sequence(), item.operationId(), item.sessionId(), leaseId, expires);
        items.put(item.operationId(), claimed);
        leaseToOperation.put(leaseId, item.operationId());
        return claimed;
    }

    private QueueItem findOldestClaimable(Instant now) {
        QueueItem oldest = null;
        for (QueueItem item : items.values()) {
            if (item.leaseId() != null
                    && item.leaseExpiresAt() != null
                    && item.leaseExpiresAt().isAfter(now)) {
                continue;
            }
            if (oldest == null || item.sequence() < oldest.sequence()) {
                oldest = item;
            }
        }
        return oldest;
    }

    @Override
    public synchronized boolean renew(String leaseId, Duration ttl) {
        String opId = leaseToOperation.get(leaseId);
        if (opId == null) {
            return false;
        }
        QueueItem item = items.get(opId);
        if (item == null || !leaseId.equals(item.leaseId())) {
            return false;
        }
        items.put(
                opId,
                new QueueItem(
                        item.sequence(),
                        item.operationId(),
                        item.sessionId(),
                        leaseId,
                        Instant.now().plus(ttl)));
        return true;
    }

    @Override
    public synchronized void complete(String leaseId, OperationStatus terminalStatus) {
        if (terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("complete requires a terminal status, got: " + terminalStatus);
        }
        String opId = leaseToOperation.remove(leaseId);
        if (opId != null) {
            completedStatus.put(opId, terminalStatus);
            items.remove(opId);
        }
    }

    @Override
    public synchronized void requeue(String leaseId) {
        String opId = leaseToOperation.remove(leaseId);
        if (opId == null) {
            return;
        }
        QueueItem item = items.get(opId);
        // Only clear the lease on the item when the requeued lease is still the active one. If a recovery worker
        // has already re-claimed the item (new leaseId), leave the new lease intact.
        if (item != null && leaseId.equals(item.leaseId())) {
            items.put(opId, new QueueItem(item.sequence(), item.operationId(), item.sessionId(), null, null));
        }
    }

    @Override
    public synchronized List<QueueItem> expiredLeases() {
        List<QueueItem> expired = new ArrayList<>();
        Instant now = Instant.now();
        for (QueueItem item : items.values()) {
            if (item.leaseId() != null
                    && item.leaseExpiresAt() != null
                    && item.leaseExpiresAt().isBefore(now)) {
                expired.add(item);
            }
        }
        return expired;
    }

    /** Returns the terminal status recorded for a completed operation, if any. Visible for testing. */
    public Optional<OperationStatus> completedStatus(String operationId) {
        return Optional.ofNullable(completedStatus.get(operationId));
    }
}
