package io.agent.helm.core.store;

import java.util.List;
import java.util.Optional;

/**
 * Workflow run records. Sub-interface of {@link RuntimeStore}.
 *
 * <p>Pagination overloads accept a {@code limit} argument. Adapters MUST honor a sane default cap (e.g. 1000) even when
 * {@code limit} is huge, to avoid unbounded result sets.
 */
public interface WorkflowRunStore {
    void saveWorkflowRun(WorkflowRunRecord run);

    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);

    /**
     * Lists workflow runs, ordered by {@code createdAt} ascending.
     *
     * @param limit the maximum number of records to return; adapters MUST honor a sane default cap (e.g. 1000) even
     *     when {@code limit} is huge.
     */
    List<WorkflowRunRecord> listWorkflowRuns(int limit);

    /** Convenience overload; equivalent to {@link #listWorkflowRuns(int) listWorkflowRuns(Integer.MAX_VALUE)}. */
    default List<WorkflowRunRecord> listWorkflowRuns() {
        return listWorkflowRuns(Integer.MAX_VALUE);
    }
}
