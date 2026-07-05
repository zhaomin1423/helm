package io.agent.helm.core.store;

import java.util.List;
import java.util.Optional;

/** Workflow run records. Sub-interface of {@link RuntimeStore}. */
public interface WorkflowRunStore {
    void saveWorkflowRun(WorkflowRunRecord run);

    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);

    List<WorkflowRunRecord> listWorkflowRuns();
}
