package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentHarnessApi;
import io.agent.helm.core.error.WorkflowNotFoundException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import io.agent.helm.runtime.internal.EventRedactor;
import io.agent.helm.runtime.internal.RuntimeErrorMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class WorkflowRuntime {
    private final Map<String, WorkflowDefinition<?, ?>> workflows;
    private final List<ModelProvider> providers;
    private final RuntimeStore store;

    private WorkflowRuntime(
            List<WorkflowDefinition<?, ?>> workflows, List<ModelProvider> providers, RuntimeStore store) {
        Map<String, WorkflowDefinition<?, ?>> workflowMap = new LinkedHashMap<>();
        for (WorkflowDefinition<?, ?> workflow : workflows) {
            workflowMap.put(workflow.name(), workflow);
        }
        this.workflows = Map.copyOf(workflowMap);
        this.providers = List.copyOf(providers);
        this.store = store;
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    public <I, O> WorkflowRunHandle<O> invoke(WorkflowInvokeRequest<I> request) {
        WorkflowDefinition<I, O> workflow = (WorkflowDefinition<I, O>) workflows.get(request.workflowName());
        if (workflow == null) {
            throw new WorkflowNotFoundException(
                    "No workflow named " + request.workflowName(),
                    Map.of("workflow", request.workflowName()),
                    Map.of());
        }

        String runId = "run_" + UUID.randomUUID();
        Instant now = Instant.now();
        store.saveWorkflowRun(new WorkflowRunRecord(
                runId, request.workflowName(), WorkflowRunStatus.RUNNING, request.input(), null, Map.of(), now, null));

        try {
            appendEvent(
                    runId,
                    1,
                    RuntimeEventType.WORKFLOW_STARTED,
                    Map.of("workflow", String.valueOf(request.workflowName())));
            WorkflowConfig config = workflow.config();
            AgentRuntime.Builder agentBuilder =
                    AgentRuntime.builder().agent(config.agent()).store(store);
            for (ModelProvider provider : providers) {
                agentBuilder.provider(provider);
            }
            AgentRuntime agentRuntime = agentBuilder.build();

            O result = workflow.run(new WorkflowContext<>() {
                @Override
                public I input() {
                    return request.input();
                }

                @Override
                public AgentHarnessApi harness() {
                    return agentRuntime.harness(config.agent().name(), "workflow-" + runId);
                }
            });

            store.saveWorkflowRun(new WorkflowRunRecord(
                    runId,
                    request.workflowName(),
                    WorkflowRunStatus.SUCCEEDED,
                    request.input(),
                    result,
                    Map.of(),
                    now,
                    Instant.now()));
            appendEventSafely(
                    runId,
                    2,
                    RuntimeEventType.WORKFLOW_SUCCEEDED,
                    Map.of("workflow", String.valueOf(request.workflowName())));
            return new WorkflowRunHandle<>(runId, result);
        } catch (Exception e) {
            Map<String, Object> error = RuntimeErrorMapper.workflowError(e);
            store.saveWorkflowRun(new WorkflowRunRecord(
                    runId,
                    request.workflowName(),
                    WorkflowRunStatus.FAILED,
                    request.input(),
                    null,
                    error,
                    now,
                    Instant.now()));
            appendEventSafely(runId, 2, RuntimeEventType.WORKFLOW_FAILED, error);
            throw new IllegalStateException("Workflow failed", e);
        }
    }

    public Optional<WorkflowRunRecord> getRun(String runId) {
        return store.loadWorkflowRun(runId);
    }

    public List<WorkflowRunRecord> listRuns() {
        return store.listWorkflowRuns();
    }

    public List<RuntimeEventRecord> getRunEvents(String runId) {
        return store.eventsForWorkflowRun(runId);
    }

    private void appendEvent(String workflowRunId, long sequence, RuntimeEventType type, Map<String, Object> payload) {
        store.appendEvent(new RuntimeEventRecord(
                "evt_" + UUID.randomUUID(),
                null,
                workflowRunId,
                sequence,
                type.type(),
                EventRedactor.redact(payload),
                Instant.now()));
    }

    private void appendEventSafely(
            String workflowRunId, long sequence, RuntimeEventType type, Map<String, Object> payload) {
        try {
            appendEvent(workflowRunId, sequence, type, payload);
        } catch (RuntimeException ignored) {
            // Event persistence must not change the workflow outcome.
        }
    }

    public static final class Builder {
        private final List<WorkflowDefinition<?, ?>> workflows = new ArrayList<>();
        private final List<ModelProvider> providers = new ArrayList<>();
        private RuntimeStore store = new InMemoryRuntimeStore();

        public Builder workflow(WorkflowDefinition<?, ?> workflow) {
            workflows.add(Objects.requireNonNull(workflow, "workflow"));
            return this;
        }

        public Builder provider(ModelProvider provider) {
            providers.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        public Builder store(RuntimeStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        public WorkflowRuntime build() {
            return new WorkflowRuntime(workflows, providers, store);
        }
    }
}
