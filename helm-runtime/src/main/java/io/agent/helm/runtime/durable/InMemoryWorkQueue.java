package io.agent.helm.runtime.durable;

import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.WorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public long enqueue(String operationId, String sessionId) {
        long seq = sequence.incrementAndGet();
        items.put(operationId, new QueueItem(seq, operationId, sessionId, null, null));
        return seq;
    }

    @Override
    public Optional<QueueItem> claim(String workerId, Duration leaseTtl) {
        Instant now = Instant.now();
        Instant expires = now.plus(leaseTtl);
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
        if (oldest == null) {
            return Optional.empty();
        }
        String leaseId = "lease_" + oldest.operationId();
        QueueItem claimed =
                new QueueItem(oldest.sequence(), oldest.operationId(), oldest.sessionId(), leaseId, expires);
        items.put(oldest.operationId(), claimed);
        leaseToOperation.put(leaseId, oldest.operationId());
        return Optional.of(claimed);
    }

    @Override
    public boolean renew(String leaseId, Duration ttl) {
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
    public void complete(String leaseId, OperationStatus terminalStatus) {
        String opId = leaseToOperation.remove(leaseId);
        if (opId != null) {
            items.remove(opId);
        }
    }

    @Override
    public void requeue(String leaseId) {
        String opId = leaseToOperation.remove(leaseId);
        if (opId == null) {
            return;
        }
        QueueItem item = items.get(opId);
        if (item != null) {
            items.put(opId, new QueueItem(item.sequence(), item.operationId(), item.sessionId(), null, null));
        }
    }

    @Override
    public List<QueueItem> expiredLeases() {
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
}
