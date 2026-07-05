package io.agent.helm.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class OperationStatusTest {
    @Test
    void queuedAndRunningAreNonTerminal() {
        assertThat(OperationStatus.QUEUED.isTerminal()).isFalse();
        assertThat(OperationStatus.RUNNING.isTerminal()).isFalse();
    }

    @Test
    void succeededFailedCancelledInterruptedAreTerminal() {
        assertThat(OperationStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(OperationStatus.FAILED.isTerminal()).isTrue();
        assertThat(OperationStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OperationStatus.INTERRUPTED.isTerminal()).isTrue();
    }
}
