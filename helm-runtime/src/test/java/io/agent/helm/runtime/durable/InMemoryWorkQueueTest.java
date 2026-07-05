package io.agent.helm.runtime.durable;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.store.OperationStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryWorkQueueTest {

    @Test
    void enqueueAndClaimFifo() {
        InMemoryWorkQueue q = new InMemoryWorkQueue();
        q.enqueue("op1", "s1");
        q.enqueue("op2", "s1");
        var first = q.claim("w1", Duration.ofSeconds(60)).orElseThrow();
        var second = q.claim("w1", Duration.ofSeconds(60)).orElseThrow();
        assertThat(first.operationId()).isEqualTo("op1");
        assertThat(second.operationId()).isEqualTo("op2");
        assertThat(q.claim("w1", Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    void completeRemovesItem() {
        InMemoryWorkQueue q = new InMemoryWorkQueue();
        q.enqueue("op1", "s1");
        var claimed = q.claim("w1", Duration.ofSeconds(60)).orElseThrow();
        q.complete(claimed.leaseId(), OperationStatus.SUCCEEDED);
        assertThat(q.claim("w1", Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    void renewExtendsLease() {
        InMemoryWorkQueue q = new InMemoryWorkQueue();
        q.enqueue("op1", "s1");
        var claimed = q.claim("w1", Duration.ofMillis(1)).orElseThrow();
        assertThat(q.renew(claimed.leaseId(), Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void requeueMakesItemClaimableAgain() {
        InMemoryWorkQueue q = new InMemoryWorkQueue();
        q.enqueue("op1", "s1");
        var claimed = q.claim("w1", Duration.ofSeconds(60)).orElseThrow();
        q.requeue(claimed.leaseId());
        var reclaimed = q.claim("w2", Duration.ofSeconds(60)).orElseThrow();
        assertThat(reclaimed.operationId()).isEqualTo("op1");
    }

    @Test
    void expiredLeasesReturned() throws InterruptedException {
        InMemoryWorkQueue q = new InMemoryWorkQueue();
        q.enqueue("op1", "s1");
        q.claim("w1", Duration.ofMillis(1));
        Thread.sleep(5);
        assertThat(q.expiredLeases()).hasSize(1);
    }
}
