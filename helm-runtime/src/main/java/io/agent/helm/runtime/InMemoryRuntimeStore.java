package io.agent.helm.runtime;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRuntimeStore implements RuntimeStore {
    private final ConcurrentMap<String, AgentSessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OperationRecord> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowRunRecord> workflowRuns = new ConcurrentHashMap<>();
    private final List<RuntimeEventRecord> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Optional<AgentSessionState> loadSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void saveSession(AgentSessionState session) {
        sessions.put(session.id(), session);
    }

    @Override
    public List<AgentSessionState> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(AgentSessionState::createdAt))
                .toList();
    }

    @Override
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void saveOperation(OperationRecord operation) {
        operations.put(operation.id(), operation);
    }

    @Override
    public Optional<OperationRecord> loadOperation(String operationId) {
        return Optional.ofNullable(operations.get(operationId));
    }

    @Override
    public List<OperationRecord> listOperations() {
        return operations.values().stream()
                .sorted(Comparator.comparing(OperationRecord::createdAt))
                .toList();
    }

    List<OperationRecord> operations() {
        return listOperations();
    }

    @Override
    public void saveWorkflowRun(WorkflowRunRecord run) {
        workflowRuns.put(run.id(), run);
    }

    @Override
    public Optional<WorkflowRunRecord> loadWorkflowRun(String runId) {
        return Optional.ofNullable(workflowRuns.get(runId));
    }

    @Override
    public List<WorkflowRunRecord> listWorkflowRuns() {
        return workflowRuns.values().stream()
                .sorted(Comparator.comparing(WorkflowRunRecord::createdAt))
                .toList();
    }

    List<WorkflowRunRecord> workflowRuns() {
        return listWorkflowRuns();
    }

    @Override
    public void appendEvent(RuntimeEventRecord event) {
        events.add(event);
    }

    @Override
    public List<RuntimeEventRecord> eventsForOperation(String operationId) {
        synchronized (events) {
            return events.stream()
                    .filter(event -> operationId.equals(event.operationId()))
                    .sorted(Comparator.comparingLong(RuntimeEventRecord::sequence))
                    .toList();
        }
    }

    @Override
    public List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId) {
        synchronized (events) {
            return events.stream()
                    .filter(event -> workflowRunId.equals(event.workflowRunId()))
                    .sorted(Comparator.comparingLong(RuntimeEventRecord::sequence))
                    .toList();
        }
    }
}
