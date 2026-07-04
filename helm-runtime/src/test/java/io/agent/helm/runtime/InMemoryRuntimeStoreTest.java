package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InMemoryRuntimeStoreTest {
    @Test
    void eventsForOperationAreReturnedBySequence() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendEvent(new RuntimeEventRecord("evt_2", "op_1", null, 2, "second", Map.of(), Instant.now()));
        store.appendEvent(new RuntimeEventRecord("evt_1", "op_1", null, 1, "first", Map.of(), Instant.now()));

        assertThat(store.eventsForOperation("op_1"))
                .extracting(RuntimeEventRecord::type)
                .containsExactly("first", "second");
    }

    @Test
    void workflowRunsAreListedByCreatedAt() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.saveWorkflowRun(new WorkflowRunRecord(
                "run_2",
                "workflow",
                WorkflowRunStatus.SUCCEEDED,
                null,
                null,
                Map.of(),
                Instant.parse("2026-06-28T00:00:02Z"),
                Instant.parse("2026-06-28T00:00:03Z")));
        store.saveWorkflowRun(new WorkflowRunRecord(
                "run_1",
                "workflow",
                WorkflowRunStatus.SUCCEEDED,
                null,
                null,
                Map.of(),
                Instant.parse("2026-06-28T00:00:01Z"),
                Instant.parse("2026-06-28T00:00:02Z")));

        assertThat(store.listWorkflowRuns()).extracting(WorkflowRunRecord::id).containsExactly("run_1", "run_2");
    }
}
