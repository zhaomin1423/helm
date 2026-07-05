package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.util.List;

/** Runtime event append-only log. Sub-interface of {@link RuntimeStore}. */
public interface EventStore {
    void appendEvent(RuntimeEventRecord event);

    List<RuntimeEventRecord> eventsForOperation(String operationId);

    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId);
}
