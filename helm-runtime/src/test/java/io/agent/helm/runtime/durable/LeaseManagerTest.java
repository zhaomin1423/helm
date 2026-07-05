package io.agent.helm.runtime.durable;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.store.WorkQueue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class LeaseManagerTest {

    @Test
    void scanRequeuesExpiredLeaseAndInvokesRecovery() throws InterruptedException {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        queue.enqueue("op1", "s1");
        queue.claim("w1", Duration.ofMillis(1));
        Thread.sleep(5);

        List<String> recovered = new ArrayList<>();
        LeaseManager manager = new LeaseManager(
                queue, Executors.newSingleThreadScheduledExecutor(), Duration.ofSeconds(1), recovered::add);

        manager.scan();

        assertThat(recovered).containsExactly("op1");
        assertThat(queue.claim("w2", Duration.ofSeconds(60)))
                .isPresent()
                .map(WorkQueue.QueueItem::operationId)
                .contains("op1");
    }

    @Test
    void scanNoopWhenNoExpiredLeases() {
        InMemoryWorkQueue queue = new InMemoryWorkQueue();
        queue.enqueue("op1", "s1");
        queue.claim("w1", Duration.ofSeconds(60));
        List<String> recovered = new ArrayList<>();
        LeaseManager manager = new LeaseManager(
                queue, Executors.newSingleThreadScheduledExecutor(), Duration.ofSeconds(1), recovered::add);

        manager.scan();

        assertThat(recovered).isEmpty();
    }
}
