package io.agent.helm.runtime;

import io.agent.helm.core.error.SessionConflictException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRuntimeStore implements RuntimeStore {
    private static final int DEFAULT_LIST_CAP = 1000;
    private final ConcurrentMap<String, AgentSessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OperationRecord> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowRunRecord> workflowRuns = new ConcurrentHashMap<>();
    private final List<RuntimeEventRecord> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Optional<AgentSessionState> loadSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Persists {@code session} with optimistic concurrency control: a session already exists for {@code session.id()}
     * with a version different from {@code session.version()} throws {@link SessionConflictException}. New sessions (no
     * existing row) are inserted directly.
     */
    @Override
    public void saveSession(AgentSessionState session) {
        sessions.compute(session.id(), (id, existing) -> {
            if (existing != null
                    && existing.version() != session.version()
                    && existing.version() + 1 != session.version()) {
                throw new SessionConflictException(
                        "Session version conflict: stored=" + existing.version() + ", requested=" + session.version(),
                        Map.of(
                                "sessionId", session.id(),
                                "storedVersion", existing.version(),
                                "requestedVersion", session.version()),
                        Map.of());
            }
            return session;
        });
    }

    @Override
    public List<AgentSessionState> listSessions(int limit) {
        int cap = sanitizeLimit(limit);
        return sessions.values().stream()
                .sorted(Comparator.comparing(AgentSessionState::createdAt))
                .limit(cap)
                .toList();
    }

    @Override
    public List<AgentSessionState> listSessions() {
        return listSessions(Integer.MAX_VALUE);
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
    public List<OperationRecord> listOperations(int limit) {
        int cap = sanitizeLimit(limit);
        return operations.values().stream()
                .sorted(Comparator.comparing(OperationRecord::createdAt))
                .limit(cap)
                .toList();
    }

    @Override
    public List<OperationRecord> listOperations(Instant after, int limit) {
        int cap = sanitizeLimit(limit);
        return operations.values().stream()
                .filter(op -> after == null || !op.createdAt().isBefore(after))
                .sorted(Comparator.comparing(OperationRecord::createdAt))
                .limit(cap)
                .toList();
    }

    @Override
    public List<OperationRecord> listOperations() {
        return listOperations(Integer.MAX_VALUE);
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
    public List<WorkflowRunRecord> listWorkflowRuns(int limit) {
        int cap = sanitizeLimit(limit);
        return workflowRuns.values().stream()
                .sorted(Comparator.comparing(WorkflowRunRecord::createdAt))
                .limit(cap)
                .toList();
    }

    @Override
    public List<WorkflowRunRecord> listWorkflowRuns() {
        return listWorkflowRuns(Integer.MAX_VALUE);
    }

    List<WorkflowRunRecord> workflowRuns() {
        return listWorkflowRuns();
    }

    @Override
    public void appendEvent(RuntimeEventRecord event) {
        events.add(event);
    }

    @Override
    public List<RuntimeEventRecord> eventsForOperation(String operationId, int limit) {
        int cap = sanitizeLimit(limit);
        synchronized (events) {
            return events.stream()
                    .filter(event -> operationId.equals(event.operationId()))
                    .sorted(Comparator.comparingLong(RuntimeEventRecord::sequence))
                    .limit(cap)
                    .toList();
        }
    }

    @Override
    public List<RuntimeEventRecord> eventsForOperation(String operationId) {
        return eventsForOperation(operationId, Integer.MAX_VALUE);
    }

    @Override
    public List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId, int limit) {
        int cap = sanitizeLimit(limit);
        synchronized (events) {
            return events.stream()
                    .filter(event -> workflowRunId.equals(event.workflowRunId()))
                    .sorted(Comparator.comparingLong(RuntimeEventRecord::sequence))
                    .limit(cap)
                    .toList();
        }
    }

    @Override
    public List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId) {
        return eventsForWorkflowRun(workflowRunId, Integer.MAX_VALUE);
    }

    private static int sanitizeLimit(int limit) {
        if (limit <= 0 || limit > DEFAULT_LIST_CAP) {
            return DEFAULT_LIST_CAP;
        }
        return limit;
    }
}
