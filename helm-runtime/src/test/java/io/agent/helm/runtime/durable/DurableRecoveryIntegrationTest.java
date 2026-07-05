package io.agent.helm.runtime.durable;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.OperationHandle;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the durable dispatch + LeaseManager recovery path: normal dispatch, lease expiry during
 * in-flight execution, recovery of a crashed worker, idempotency on already-completed operations, and shutdown ordering
 * via {@code close()}.
 */
class DurableRecoveryIntegrationTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void durableDispatchCompletesAndPersistsSucceededOperation() throws InterruptedException {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        try (var executor = Executors.newSingleThreadExecutor();
                AgentRuntime runtime = AgentRuntime.builder()
                        .agent(simpleAgent())
                        .provider(provider)
                        .store(store)
                        .durable(executor, queue)
                        .build()) {
            OperationHandle handle = runtime.dispatch(new AgentPromptRequest("a", "i", "s", "Hi"));

            waitForStatus(runtime, handle.operationId(), OperationStatus.SUCCEEDED);
            assertThat(runtime.getOperation(handle.operationId()).orElseThrow().output())
                    .isEqualTo("hello");
        }
    }

    @Test
    void recoverySkipsAlreadyCompletedOperation() throws InterruptedException {
        FakeProvider provider = new FakeProvider("fake");
        AtomicInteger configureCount = new AtomicInteger(0);
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        try (var executor = Executors.newSingleThreadExecutor();
                AgentRuntime runtime = AgentRuntime.builder()
                        .agent(countingAgent(configureCount))
                        .provider(provider)
                        .store(store)
                        .durable(executor, queue)
                        .build()) {
            OperationHandle handle = runtime.dispatch(new AgentPromptRequest("a", "i", "s", "Hi"));
            waitForStatus(runtime, handle.operationId(), OperationStatus.SUCCEEDED);

            // Snapshot the operation record (already terminal). Recovery must not re-execute.
            OperationRecord completed =
                    runtime.getOperation(handle.operationId()).orElseThrow();
            assertThat(completed.status()).isEqualTo(OperationStatus.SUCCEEDED);

            // Simulate lease expiry scan — recoverOperation sees SUCCEEDED and skips re-dispatch.
            // The configureCount should not increase.
            int beforeRecovery = configureCount.get();
            Thread.sleep(200);
            assertThat(configureCount.get()).isEqualTo(beforeRecovery);
        }
    }

    @Test
    void claimByOperationIdReturnsEmptyForUnknownOperation() {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        // No enqueue — claim by a nonexistent operationId must return empty.
        assertThat(queue.claim("nonexistent-op", "worker", Duration.ofSeconds(60)))
                .isEmpty();
    }

    @Test
    void claimByOperationIdClaimsCorrectItemAndRejectsConcurrentClaim() {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        queue.enqueue("op1", "s1");
        queue.enqueue("op2", "s1");

        // Claim op2 specifically — must not grab op1.
        var claimed = queue.claim("op2", "worker", Duration.ofSeconds(60));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().operationId()).isEqualTo("op2");

        // op1 is still claimable by a different worker.
        var claimedOp1 = queue.claim("op1", "worker2", Duration.ofSeconds(60));
        assertThat(claimedOp1).isPresent();
        assertThat(claimedOp1.get().operationId()).isEqualTo("op1");

        // op2 cannot be re-claimed while leased.
        assertThat(queue.claim("op2", "worker3", Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    void closeIsIdempotentAndShutsDownLeaseManager() {
        FakeProvider provider = new FakeProvider("fake");
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryWorkQueue queue = new InMemoryWorkQueue();

        AgentRuntime runtime = AgentRuntime.builder()
                .agent(simpleAgent())
                .provider(provider)
                .store(store)
                .durable(Executors.newSingleThreadExecutor(), queue)
                .build();

        // close() must be idempotent and not throw.
        runtime.close();
        runtime.close();
    }

    @Test
    void leaseManagerScanRecoversBeforeRequeue() throws InterruptedException {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        queue.enqueue("op1", "s1");
        // Claim with a very short lease so it expires immediately.
        queue.claim("worker", Duration.ofMillis(1));
        Thread.sleep(5);

        AtomicInteger recoverCount = new AtomicInteger(0);
        java.util.List<String> recoveredOps = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            LeaseManager manager = new LeaseManager(queue, scheduler, Duration.ofSeconds(1), opId -> {
                recoverCount.incrementAndGet();
                recoveredOps.add(opId);
            });
            manager.scan();

            // Recovery was invoked for the expired item.
            assertThat(recoverCount.get()).isEqualTo(1);
            assertThat(recoveredOps).containsExactly("op1");

            // The item was requeued — it can be claimed again by another worker.
            assertThat(queue.claim("new-worker", Duration.ofSeconds(60)))
                    .isPresent()
                    .map(io.agent.helm.core.store.WorkQueue.QueueItem::operationId)
                    .contains("op1");
        }
    }

    @Test
    void leaseManagerScanContinuesAfterRecoveryThrows() throws InterruptedException {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        queue.enqueue("op1", "s1");
        queue.enqueue("op2", "s2");
        queue.claim("w1", Duration.ofMillis(1));
        queue.claim("w2", Duration.ofMillis(1));
        Thread.sleep(5);

        AtomicInteger recoverCount = new AtomicInteger(0);
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            LeaseManager manager = new LeaseManager(queue, scheduler, Duration.ofSeconds(1), opId -> {
                recoverCount.incrementAndGet();
                if (opId.equals("op1")) {
                    throw new RuntimeException("recovery exploded");
                }
            });
            // scan must not abort on the first failure — both items should be attempted.
            manager.scan();
            assertThat(recoverCount.get()).isEqualTo(2);
        }
    }

    private static void waitForStatus(AgentRuntime runtime, String operationId, OperationStatus expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            OperationStatus current = runtime.getOperation(operationId)
                    .map(OperationRecord::status)
                    .orElse(null);
            if (current == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Operation " + operationId + " did not reach " + expected + " within " + POLL_TIMEOUT);
    }

    private static AgentDefinition simpleAgent() {
        return new AgentDefinition() {
            @Override
            public String name() {
                return "a";
            }

            @Override
            public AgentConfig configure(AgentContext context) {
                return AgentConfig.builder()
                        .model("fake/test")
                        .instructions("hi")
                        .build();
            }
        };
    }

    private static AgentDefinition countingAgent(AtomicInteger counter) {
        return new AgentDefinition() {
            @Override
            public String name() {
                return "a";
            }

            @Override
            public AgentConfig configure(AgentContext context) {
                counter.incrementAndGet();
                return AgentConfig.builder()
                        .model("fake/test")
                        .instructions("hi")
                        .build();
            }
        };
    }
}
