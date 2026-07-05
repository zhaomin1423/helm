package io.agent.helm.runtime;

import io.agent.helm.core.admission.RateLimiter;
import io.agent.helm.core.agent.AgentHarnessApi;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.WorkflowException;
import io.agent.helm.core.error.WorkflowNotFoundException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.security.HelmAuthorizer;
import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import io.agent.helm.runtime.internal.EventRedactor;
import io.agent.helm.runtime.internal.JsonCodec;
import io.agent.helm.runtime.internal.RuntimeErrorMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkflowRuntime {
    private final Map<String, WorkflowDefinition<?, ?>> workflows;
    private final List<ModelProvider> providers;
    private final RuntimeStore store;
    private final HelmAuthorizer authorizer;
    private final RateLimiter rateLimiter;
    private final MemoryStore memoryStore;
    private final HelmSecurityContext defaultSecurityContext;

    private WorkflowRuntime(
            List<WorkflowDefinition<?, ?>> workflows,
            List<ModelProvider> providers,
            RuntimeStore store,
            HelmAuthorizer authorizer,
            RateLimiter rateLimiter,
            MemoryStore memoryStore,
            HelmSecurityContext defaultSecurityContext) {
        Map<String, WorkflowDefinition<?, ?>> workflowMap = new LinkedHashMap<>();
        for (WorkflowDefinition<?, ?> workflow : workflows) {
            workflowMap.put(workflow.name(), workflow);
        }
        this.workflows = Map.copyOf(workflowMap);
        this.providers = List.copyOf(providers);
        this.store = store;
        this.authorizer = authorizer;
        this.rateLimiter = rateLimiter;
        this.memoryStore = memoryStore;
        this.defaultSecurityContext = defaultSecurityContext;
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
        String inputJson = JsonCodec.encode(request.input());
        store.saveWorkflowRun(new WorkflowRunRecord(
                runId, request.workflowName(), WorkflowRunStatus.RUNNING, inputJson, null, Map.of(), now, null));

        AtomicInteger eventSequence = new AtomicInteger(0);
        try {
            appendEvent(
                    runId,
                    eventSequence.incrementAndGet(),
                    RuntimeEventType.WORKFLOW_STARTED,
                    Map.of("workflow", String.valueOf(request.workflowName())));
            WorkflowConfig config = workflow.config();
            AgentRuntime.Builder agentBuilder =
                    AgentRuntime.builder().agent(config.agent()).store(store);
            for (ModelProvider provider : providers) {
                agentBuilder.provider(provider);
            }
            // Thread admission/memory components from the WorkflowRuntime into the inner AgentRuntime so workflow
            // prompts honor the same authorizer, rate limiter, and memory store.
            if (authorizer != null) {
                agentBuilder.authorizer(authorizer);
            }
            if (rateLimiter != null) {
                agentBuilder.rateLimiter(rateLimiter);
            }
            if (memoryStore != null) {
                agentBuilder.memoryStore(memoryStore);
            }
            if (defaultSecurityContext != null) {
                agentBuilder.defaultSecurityContext(defaultSecurityContext);
            }
            AgentRuntime agentRuntime = agentBuilder.build();

            O result;
            try {
                result = workflow.run(new WorkflowContext<>() {
                    @Override
                    public I input() {
                        return request.input();
                    }

                    @Override
                    public AgentHarnessApi harness() {
                        return agentRuntime.harness(config.agent().name(), "workflow-" + runId);
                    }

                    @Override
                    public String operationId() {
                        return runId;
                    }

                    @Override
                    public Clock clock() {
                        return Clock.systemUTC();
                    }

                    @Override
                    public void event(String type, Map<String, Object> details) {
                        // Record workflow-emitted progress events; the custom type string is carried in the payload
                        // since RuntimeEventType is a fixed enum.
                        appendEventSafely(
                                runId,
                                eventSequence.incrementAndGet(),
                                RuntimeEventType.WORKFLOW_STARTED,
                                Map.of(
                                        "eventType", type == null ? "" : type,
                                        "details", details == null ? Map.of() : EventRedactor.redact(details)));
                    }
                });
            } finally {
                // Always close the inner AgentRuntime to release its lease manager / scheduler.
                agentRuntime.close();
            }

            String outputJson = JsonCodec.encode(result);
            store.saveWorkflowRun(new WorkflowRunRecord(
                    runId,
                    request.workflowName(),
                    WorkflowRunStatus.SUCCEEDED,
                    inputJson,
                    outputJson,
                    Map.of(),
                    now,
                    Instant.now()));
            appendEventSafely(
                    runId,
                    eventSequence.incrementAndGet(),
                    RuntimeEventType.WORKFLOW_SUCCEEDED,
                    Map.of("workflow", String.valueOf(request.workflowName())));
            return new WorkflowRunHandle<>(runId, result);
        } catch (HelmException e) {
            // Re-throw HelmException causes unchanged so callers see the original code.
            Map<String, Object> error = RuntimeErrorMapper.workflowError(e);
            store.saveWorkflowRun(new WorkflowRunRecord(
                    runId,
                    request.workflowName(),
                    WorkflowRunStatus.FAILED,
                    inputJson,
                    null,
                    error,
                    now,
                    Instant.now()));
            appendEventSafely(runId, eventSequence.incrementAndGet(), RuntimeEventType.WORKFLOW_FAILED, error);
            throw e;
        } catch (Exception e) {
            // Wrap only non-Helm throwables in WorkflowException, preserving the cause.
            Map<String, Object> error = RuntimeErrorMapper.workflowError(e);
            store.saveWorkflowRun(new WorkflowRunRecord(
                    runId,
                    request.workflowName(),
                    WorkflowRunStatus.FAILED,
                    inputJson,
                    null,
                    error,
                    now,
                    Instant.now()));
            appendEventSafely(runId, eventSequence.incrementAndGet(), RuntimeEventType.WORKFLOW_FAILED, error);
            throw new WorkflowException(
                    "Workflow failed: " + request.workflowName(),
                    Map.of("workflow", request.workflowName()),
                    Map.of(),
                    e);
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
                type,
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
        private HelmAuthorizer authorizer;
        private RateLimiter rateLimiter;
        private MemoryStore memoryStore;
        private HelmSecurityContext defaultSecurityContext;

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

        /** Threads an authorizer into the inner AgentRuntime built for each workflow run. */
        public Builder authorizer(HelmAuthorizer authorizer) {
            this.authorizer = authorizer;
            return this;
        }

        /** Threads a rate limiter into the inner AgentRuntime built for each workflow run. */
        public Builder rateLimiter(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /** Threads a memory store into the inner AgentRuntime built for each workflow run. */
        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
            return this;
        }

        /** Default security context for workflow runs. */
        public Builder defaultSecurityContext(HelmSecurityContext defaultSecurityContext) {
            this.defaultSecurityContext = defaultSecurityContext;
            return this;
        }

        public WorkflowRuntime build() {
            return new WorkflowRuntime(
                    workflows, providers, store, authorizer, rateLimiter, memoryStore, defaultSecurityContext);
        }
    }
}
