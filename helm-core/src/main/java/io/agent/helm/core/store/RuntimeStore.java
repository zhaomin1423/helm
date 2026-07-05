package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.List;
import java.util.Optional;

public interface RuntimeStore {
    Optional<AgentSessionState> loadSession(String sessionId);

    void saveSession(AgentSessionState session);

    /** Lists all sessions, ordered by {@code createdAt} ascending. */
    List<AgentSessionState> listSessions();

    /** Deletes the session with the given id. Deleting an unknown id is a no-op. */
    void deleteSession(String sessionId);

    void saveOperation(OperationRecord operation);

    Optional<OperationRecord> loadOperation(String operationId);

    List<OperationRecord> listOperations();

    void saveWorkflowRun(WorkflowRunRecord run);

    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);

    List<WorkflowRunRecord> listWorkflowRuns();

    void appendEvent(RuntimeEventRecord event);

    List<RuntimeEventRecord> eventsForOperation(String operationId);

    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId);
}
