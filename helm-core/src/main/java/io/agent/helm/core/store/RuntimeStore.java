package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.List;
import java.util.Optional;

public interface RuntimeStore {
    Optional<AgentSessionState> loadSession(String sessionId);

    void saveSession(AgentSessionState session);

    void saveOperation(OperationRecord operation);

    Optional<OperationRecord> loadOperation(String operationId);

    void saveWorkflowRun(WorkflowRunRecord run);

    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);

    void appendEvent(RuntimeEventRecord event);

    List<RuntimeEventRecord> eventsForOperation(String operationId);

    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId);
}
