package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.List;

/**
 * Runtime event append-only log. Sub-interface of {@link RuntimeStore}.
 *
 * <p>Pagination overloads accept a {@code limit} argument. Adapters MUST honor a sane default cap (e.g. 1000) even when
 * {@code limit} is huge, to avoid unbounded result sets.
 */
public interface EventStore {
    void appendEvent(RuntimeEventRecord event);

    /**
     * Returns events recorded for the given operation, ordered by sequence ascending.
     *
     * @param limit the maximum number of events to return; adapters MUST honor a sane default cap (e.g. 1000) even when
     *     {@code limit} is huge.
     */
    List<RuntimeEventRecord> eventsForOperation(String operationId, int limit);

    /**
     * Returns events recorded for the given workflow run, ordered by sequence ascending.
     *
     * @param limit the maximum number of events to return; adapters MUST honor a sane default cap (e.g. 1000) even when
     *     {@code limit} is huge.
     */
    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId, int limit);

    /**
     * Convenience overload; equivalent to {@link #eventsForOperation(String, int) eventsForOperation(id,
     * Integer.MAX_VALUE)}.
     */
    default List<RuntimeEventRecord> eventsForOperation(String operationId) {
        return eventsForOperation(operationId, Integer.MAX_VALUE);
    }

    /**
     * Convenience overload; equivalent to {@link #eventsForWorkflowRun(String, int) eventsForWorkflowRun(id,
     * Integer.MAX_VALUE)}.
     */
    default List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId) {
        return eventsForWorkflowRun(workflowRunId, Integer.MAX_VALUE);
    }
}
