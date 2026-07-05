package io.agent.helm.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.message.HelmMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract tests for {@link RuntimeStore}. Store adapters extend this class and implement
 * {@link #createStore()}. Both the in-memory and JDBC implementations must pass.
 */
public abstract class RuntimeStoreContractTest {
    private static final Instant T1 = Instant.ofEpochSecond(1000);
    private static final Instant T2 = Instant.ofEpochSecond(2000);

    protected abstract RuntimeStore createStore();

    @Test
    void saveAndLoadSession() {
        RuntimeStore store = createStore();
        AgentSessionState session = new AgentSessionState(
                "s1", "agent", "instance-1", "default", 1, List.of(HelmMessage.user("hi")), T1, T2);

        store.saveSession(session);

        assertThat(store.loadSession("s1")).contains(session);
    }

    @Test
    void loadMissingSessionReturnsEmpty() {
        assertThat(createStore().loadSession("nope")).isEmpty();
    }

    @Test
    void listSessionsSortedByCreatedAtAscending() {
        RuntimeStore store = createStore();
        store.saveSession(new AgentSessionState("s2", "agent", "instance-1", "later", 1, List.of(), T2, T2));
        store.saveSession(new AgentSessionState("s1", "agent", "instance-1", "earlier", 1, List.of(), T1, T1));

        assertThat(store.listSessions()).extracting(AgentSessionState::id).containsExactly("s1", "s2");
    }

    @Test
    void deleteSessionRemovesSession() {
        RuntimeStore store = createStore();
        store.saveSession(new AgentSessionState("s1", "agent", "instance-1", "default", 1, List.of(), T1, T1));

        store.deleteSession("s1");

        assertThat(store.loadSession("s1")).isEmpty();
        assertThat(store.listSessions()).isEmpty();
    }

    @Test
    void deleteUnknownSessionIsNoOp() {
        RuntimeStore store = createStore();
        store.deleteSession("nope");

        assertThat(store.listSessions()).isEmpty();
    }

    @Test
    void saveAndLoadOperation() {
        RuntimeStore store = createStore();
        OperationRecord op = new OperationRecord(
                "op1", "s1", "PROMPT", OperationStatus.SUCCEEDED, "input", "output", Map.of(), T1, T2);

        store.saveOperation(op);

        assertThat(store.loadOperation("op1")).contains(op);
    }

    @Test
    void loadMissingOperationReturnsEmpty() {
        assertThat(createStore().loadOperation("nope")).isEmpty();
    }

    @Test
    void listOperationsSortedByCreatedAtAscending() {
        RuntimeStore store = createStore();
        store.saveOperation(
                new OperationRecord("op2", "s1", "PROMPT", OperationStatus.SUCCEEDED, "in2", "out2", Map.of(), T2, T2));
        store.saveOperation(
                new OperationRecord("op1", "s1", "PROMPT", OperationStatus.RUNNING, "in1", null, Map.of(), T1, null));

        assertThat(store.listOperations()).extracting(OperationRecord::id).containsExactly("op1", "op2");
    }

    @Test
    void saveAndLoadWorkflowRun() {
        RuntimeStore store = createStore();
        WorkflowRunRecord run =
                new WorkflowRunRecord("run1", "workflow", WorkflowRunStatus.SUCCEEDED, "in", "out", Map.of(), T1, T2);

        store.saveWorkflowRun(run);

        assertThat(store.loadWorkflowRun("run1")).contains(run);
    }

    @Test
    void loadMissingWorkflowRunReturnsEmpty() {
        assertThat(createStore().loadWorkflowRun("nope")).isEmpty();
    }

    @Test
    void listWorkflowRunsSortedByCreatedAtAscending() {
        RuntimeStore store = createStore();
        store.saveWorkflowRun(
                new WorkflowRunRecord("run2", "wf", WorkflowRunStatus.SUCCEEDED, "in2", "out2", Map.of(), T2, T2));
        store.saveWorkflowRun(
                new WorkflowRunRecord("run1", "wf", WorkflowRunStatus.RUNNING, "in1", null, Map.of(), T1, null));

        assertThat(store.listWorkflowRuns()).extracting(WorkflowRunRecord::id).containsExactly("run1", "run2");
    }

    @Test
    void eventsForOperationOrderedBySequence() {
        RuntimeStore store = createStore();
        store.appendEvent(new RuntimeEventRecord("e2", "op1", null, 2, "second", Map.of("k", "v"), T2));
        store.appendEvent(new RuntimeEventRecord("e1", "op1", null, 1, "first", Map.of(), T1));

        assertThat(store.eventsForOperation("op1"))
                .extracting(RuntimeEventRecord::id)
                .containsExactly("e1", "e2");
    }

    @Test
    void eventsForWorkflowRunOrderedBySequence() {
        RuntimeStore store = createStore();
        store.appendEvent(new RuntimeEventRecord("e2", null, "run1", 2, "second", Map.of(), T2));
        store.appendEvent(new RuntimeEventRecord("e1", null, "run1", 1, "first", Map.of(), T1));

        assertThat(store.eventsForWorkflowRun("run1"))
                .extracting(RuntimeEventRecord::id)
                .containsExactly("e1", "e2");
    }

    @Test
    void eventsForUnknownIdReturnEmpty() {
        RuntimeStore store = createStore();
        assertThat(store.eventsForOperation("nope")).isEmpty();
        assertThat(store.eventsForWorkflowRun("nope")).isEmpty();
    }

    @Test
    void saveReplacesExistingRecord() {
        RuntimeStore store = createStore();
        store.saveOperation(
                new OperationRecord("op1", "s1", "PROMPT", OperationStatus.RUNNING, "in", null, Map.of(), T1, null));
        store.saveOperation(
                new OperationRecord("op1", "s1", "PROMPT", OperationStatus.SUCCEEDED, "in", "out", Map.of(), T1, T2));

        assertThat(store.loadOperation("op1")).hasValueSatisfying(op -> {
            assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
            assertThat(op.output()).isEqualTo("out");
        });
    }
}
