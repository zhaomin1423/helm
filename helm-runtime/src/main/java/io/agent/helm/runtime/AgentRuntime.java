package io.agent.helm.runtime;

import io.agent.helm.core.admission.AcquisitionResult;
import io.agent.helm.core.admission.RateLimitKey;
import io.agent.helm.core.admission.RateLimiter;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.error.AgentNotFoundException;
import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.RateLimitExceededException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.SessionConflictException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.security.AuthorizationResult;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.security.HelmAuthorizer;
import io.agent.helm.core.security.HelmResource;
import io.agent.helm.core.security.HelmSecurityContext;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkQueue;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.core.tool.ToolLogger;
import io.agent.helm.engine.AgentEngine;
import io.agent.helm.engine.AgentEngineRequest;
import io.agent.helm.engine.AgentEngineResult;
import io.agent.helm.engine.EngineEvent;
import io.agent.helm.engine.EngineEventListener;
import io.agent.helm.engine.ToolExecutor;
import io.agent.helm.runtime.durable.LeaseManager;
import io.agent.helm.runtime.internal.EventRedactor;
import io.agent.helm.runtime.internal.JsonCodec;
import io.agent.helm.runtime.internal.ProviderRegistry;
import io.agent.helm.runtime.internal.RuntimeErrorMapper;
import io.agent.helm.runtime.memory.SaveMemoryTool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentRuntime implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_TURNS = 8;
    private static final int MAX_SESSION_CONFLICT_RETRIES = 1;

    private final Map<String, AgentDefinition> agents;
    private final ProviderRegistry providers;
    private final RuntimeStore store;
    private final MemoryStore memoryStore;
    private final int maxSessionMessages;
    private final HelmAuthorizer authorizer;
    private final RateLimiter rateLimiter;
    private final HelmSecurityContext defaultSecurityContext;
    private final ExecutorService executor;
    private final WorkQueue workQueue;
    private final LeaseManager leaseManager;
    private final ScheduledExecutorService leaseScheduler;
    private final AgentEngine engine = new AgentEngine();
    private final ConcurrentMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private AgentRuntime(
            List<AgentDefinition> agents,
            List<ModelProvider> providers,
            RuntimeStore store,
            MemoryStore memoryStore,
            int maxSessionMessages,
            HelmAuthorizer authorizer,
            RateLimiter rateLimiter,
            HelmSecurityContext defaultSecurityContext,
            ExecutorService executor,
            WorkQueue workQueue) {
        Map<String, AgentDefinition> agentMap = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            agentMap.put(agent.name(), agent);
        }
        this.agents = Map.copyOf(agentMap);
        this.providers = new ProviderRegistry(providers);
        this.store = store;
        this.memoryStore = memoryStore;
        this.maxSessionMessages = maxSessionMessages;
        this.authorizer = authorizer == null ? HelmAuthorizer.allowAll() : authorizer;
        this.rateLimiter = rateLimiter == null ? RateLimiter.unlimited() : rateLimiter;
        this.defaultSecurityContext =
                defaultSecurityContext == null ? HelmSecurityContext.anonymous() : defaultSecurityContext;
        this.executor = executor;
        this.workQueue = workQueue;
        if (executor != null && workQueue != null) {
            this.leaseScheduler = Executors.newSingleThreadScheduledExecutor();
            this.leaseManager =
                    new LeaseManager(workQueue, leaseScheduler, Duration.ofSeconds(10), this::recoverOperation);
            this.leaseManager.start();
        } else {
            this.leaseScheduler = null;
            this.leaseManager = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentHarness harness(String agentName, String instanceId) {
        return new AgentHarness(this, agentName, instanceId);
    }

    public PromptResult prompt(AgentPromptRequest request) {
        String operationId = "op_" + UUID.randomUUID();
        PromptExecution execution = executePrompt(request, operationId, defaultSecurityContext);
        return new PromptResult(execution.operationId(), execution.text());
    }

    /**
     * Streams prompt events to the subscriber as they arrive (one {@link PromptStreamEvent.ContentDelta} per model
     * token). The operation is admitted, the engine stream is forwarded, and the terminal record is persisted before
     * {@link PromptStreamEvent.OperationCompleted} is emitted. @Preview streamed session persistence currently excludes
     * tool-call messages; planned.
     */
    @io.agent.helm.core.annotation.Preview
    public Flow.Publisher<PromptStreamEvent> promptStream(AgentPromptRequest request) {
        return subscriber -> {
            SubmissionPublisher<PromptStreamEvent> pub = new SubmissionPublisher<>();
            pub.subscribe(subscriber);
            String operationId = "op_" + UUID.randomUUID();
            String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());
            AtomicInteger eventSequence = new AtomicInteger(0);
            Instant start = Instant.now();
            // Acquire the session lock BEFORE the try block so the finally clause only releases when this call
            // actually acquired the entry (mirrors executePrompt). A busy session throws without touching the map.
            if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
                throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
            }
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
            try {
                authorize(defaultSecurityContext, HelmAction.PROMPT, HelmResource.of("AGENT", request.agentName()));
                acquireRate(RateLimitKey.principal(defaultSecurityContext.principal()));
                store.saveOperation(new OperationRecord(
                        operationId,
                        sessionId,
                        HelmAction.PROMPT,
                        OperationStatus.RUNNING,
                        JsonCodec.encodeRequest(request),
                        null,
                        Map.of(),
                        start,
                        null));
                appendEvent(
                        operationId,
                        null,
                        eventSequence.incrementAndGet(),
                        RuntimeEventType.OPERATION_STARTED,
                        Map.of("text", String.valueOf(request.text())));
                AgentDefinition agentDef = agent(request.agentName());
                AgentConfig config = agentDef.configure(new AgentContext(request.agentName(), request.instanceId()));
                ModelProvider provider = providers.resolve(config.model());
                AgentSessionState current = store.loadSession(sessionId)
                        .orElseGet(() -> new AgentSessionState(
                                sessionId,
                                request.agentName(),
                                request.instanceId(),
                                request.sessionName(),
                                0,
                                List.of(),
                                start,
                                start));
                List<HelmMessage> messages = new ArrayList<>(current.messages());
                messages.add(HelmMessage.user(request.text()));
                messages = trimHistory(messages);
                String scopeId = memoryScopeId(request.agentName(), request.instanceId());
                List<Tool<?, ?>> tools = effectiveTools(config, scopeId);
                String instructions = instructionsWithMemories(config.instructions(), scopeId);
                List<ToolDescriptor> toolDescriptors =
                        tools.stream().map(ToolDescriptor::from).toList();
                AgentEngineRequest engineRequest = new AgentEngineRequest(
                        config.model(),
                        instructions,
                        toolDescriptors,
                        messages,
                        provider,
                        toolExecutor(tools, operationId, defaultSecurityContext, config.sandbox()),
                        DEFAULT_TIMEOUT,
                        DEFAULT_MAX_TURNS,
                        buildEngineListener(operationId, eventSequence));
                StringBuilder text = new StringBuilder();
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<List<HelmMessage>> completedMessages = new AtomicReference<>();
                AtomicReference<TokenUsage> completedUsage = new AtomicReference<>(new TokenUsage(0, 0));
                engine.runStream(engineRequest, (msgs, usage) -> {
                            completedMessages.set(msgs);
                            completedUsage.set(usage);
                        })
                        .subscribe(new Flow.Subscriber<>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscriptionRef.set(subscription);
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(PromptStreamEvent event) {
                                if (event instanceof PromptStreamEvent.ContentDelta delta) {
                                    text.append(delta.text());
                                }
                                pub.submit(event);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                failure.set(throwable);
                                done.countDown();
                            }

                            @Override
                            public void onComplete() {
                                done.countDown();
                            }
                        });
                long streamTimeoutMs = DEFAULT_TIMEOUT.toMillis() * DEFAULT_MAX_TURNS + 1000;
                boolean completed;
                try {
                    completed = done.await(streamTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelSubscription(subscriptionRef);
                    throw new io.agent.helm.core.error.EngineInterruptedException(
                            "Interrupted while streaming prompt", Map.of(), Map.of());
                }
                if (!completed) {
                    // Stream timed out — cancel the in-flight subscription and treat as a failure.
                    cancelSubscription(subscriptionRef);
                    throw new io.agent.helm.core.error.TurnTimeoutException(
                            "Prompt stream timed out after " + streamTimeoutMs + "ms",
                            Map.of("timeoutMs", streamTimeoutMs),
                            Map.of());
                }
                Throwable throwable = failure.get();
                if (throwable != null) {
                    throw throwable instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new RuntimeException(throwable);
                }
                List<HelmMessage> updated =
                        completedMessages.get() != null ? completedMessages.get() : new ArrayList<>(messages);
                saveSessionWithRetry(sessionId, request, current, updated, start);
                store.saveOperation(new OperationRecord(
                        operationId,
                        sessionId,
                        HelmAction.PROMPT,
                        OperationStatus.SUCCEEDED,
                        JsonCodec.encodeRequest(request),
                        text.toString(),
                        Map.of(),
                        start,
                        Instant.now()));
                appendEventSafely(
                        operationId,
                        null,
                        eventSequence.incrementAndGet(),
                        RuntimeEventType.OPERATION_SUCCEEDED,
                        Map.of("text", text.toString()));
                pub.submit(
                        new PromptStreamEvent.OperationCompleted(operationId, text.toString(), completedUsage.get()));
                pub.close();
            } catch (RuntimeException e) {
                String code = (e instanceof HelmException he) ? he.code() : "INTERNAL_ERROR";
                Map<String, Object> error = RuntimeErrorMapper.operationError(e);
                store.saveOperation(new OperationRecord(
                        operationId,
                        sessionId,
                        HelmAction.PROMPT,
                        OperationStatus.FAILED,
                        JsonCodec.encodeRequest(request),
                        null,
                        error,
                        start,
                        Instant.now()));
                appendEventSafely(
                        operationId, null, eventSequence.incrementAndGet(), RuntimeEventType.OPERATION_FAILED, error);
                pub.submit(new PromptStreamEvent.OperationFailed(operationId, code, String.valueOf(e.getMessage())));
                pub.closeExceptionally(e);
            } finally {
                activeSessions.remove(sessionId);
            }
        };
    }

    public OperationHandle dispatch(AgentPromptRequest request) {
        String operationId = "op_" + UUID.randomUUID();
        if (executor != null) {
            return dispatchDurable(request, operationId);
        }
        try {
            executePrompt(request, operationId, defaultSecurityContext);
        } catch (RuntimeException e) {
            OperationStatus status = store.loadOperation(operationId)
                    .map(OperationRecord::status)
                    .orElse(OperationStatus.FAILED);
            return new OperationHandle(operationId, status);
        }
        return new OperationHandle(operationId, OperationStatus.SUCCEEDED);
    }

    /** @Preview durable async dispatch: enqueue + execute on the executor; lease/recovery remain post-GA. */
    @io.agent.helm.core.annotation.Preview
    private OperationHandle dispatchDurable(AgentPromptRequest request, String operationId) {
        String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());
        Instant now = Instant.now();
        // Store the JSON-encoded request in input so recovery can reconstruct the request without parsing sessionId.
        store.saveOperation(new OperationRecord(
                operationId,
                sessionId,
                HelmAction.PROMPT,
                OperationStatus.QUEUED,
                JsonCodec.encodeRequest(request),
                null,
                Map.of(),
                now,
                null));
        if (workQueue != null) {
            workQueue.enqueue(operationId, sessionId);
        }
        executor.execute(() -> processOperation(request, operationId));
        // Best-effort: report QUEUED; the operation may already have transitioned if the executor runs synchronously.
        OperationStatus current =
                store.loadOperation(operationId).map(OperationRecord::status).orElse(OperationStatus.QUEUED);
        return new OperationHandle(operationId, current);
    }

    private void processOperation(AgentPromptRequest request, String operationId) {
        String leaseId = operationId;
        if (workQueue != null) {
            Optional<WorkQueue.QueueItem> claimed = workQueue.claim(operationId, "worker", Duration.ofSeconds(60));
            if (claimed.isEmpty()) {
                // Nothing to claim for this operation — another worker may have already processed it. Abort without
                // executing the prompt.
                return;
            }
            leaseId = claimed.get().leaseId();
        }
        try {
            executePrompt(request, operationId, defaultSecurityContext);
            if (workQueue != null) {
                workQueue.complete(leaseId, OperationStatus.SUCCEEDED);
            }
        } catch (RuntimeException e) {
            if (workQueue != null) {
                workQueue.complete(leaseId, OperationStatus.FAILED);
            }
        }
    }

    /** Recovery: re-dispatch an operation whose lease expired. Skips terminal-status operations for idempotency. */
    private void recoverOperation(String operationId) {
        store.loadOperation(operationId).ifPresent(op -> {
            if (op.status().isTerminal()) {
                // Already completed — do not re-execute (idempotency).
                return;
            }
            AgentPromptRequest request = JsonCodec.decodeRequest(op.input());
            executor.execute(() -> processOperation(request, operationId));
        });
    }

    public Optional<OperationRecord> getOperation(String operationId) {
        return store.loadOperation(operationId);
    }

    public List<RuntimeEventRecord> getOperationEvents(String operationId) {
        return store.eventsForOperation(operationId);
    }

    public List<OperationRecord> listOperations() {
        return store.listOperations();
    }

    /** Lists all persisted agent sessions, ordered by creation time. */
    public List<AgentSessionState> listSessions() {
        authorize(defaultSecurityContext, HelmAction.LIST_SESSIONS, HelmResource.of("RUNTIME", ""));
        return store.listSessions();
    }

    /** Loads a persisted session (including its message history) by id. */
    public Optional<AgentSessionState> getSession(String sessionId) {
        authorize(defaultSecurityContext, HelmAction.READ_SESSION, HelmResource.of("SESSION", sessionId));
        return store.loadSession(sessionId);
    }

    /** Deletes a session and its history so the next prompt starts fresh. */
    public void resetSession(String sessionId) {
        authorize(defaultSecurityContext, HelmAction.RESET_SESSION, HelmResource.of("SESSION", sessionId));
        if (activeSessions.containsKey(sessionId)) {
            throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
        }
        store.deleteSession(sessionId);
    }

    private PromptExecution executePrompt(
            AgentPromptRequest request, String operationId, HelmSecurityContext securityContext) {
        String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());
        authorize(securityContext, HelmAction.PROMPT, HelmResource.of("AGENT", request.agentName()));
        acquireRate(RateLimitKey.principal(securityContext.principal()));
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
        }
        Instant start = Instant.now();
        AtomicInteger eventSequence = new AtomicInteger(0);

        try {
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    HelmAction.PROMPT,
                    OperationStatus.RUNNING,
                    JsonCodec.encodeRequest(request),
                    null,
                    Map.of(),
                    start,
                    null));
            appendEvent(
                    operationId,
                    null,
                    eventSequence.incrementAndGet(),
                    RuntimeEventType.OPERATION_STARTED,
                    Map.of("text", String.valueOf(request.text())));

            AgentDefinition agentDef = agent(request.agentName());
            AgentConfig config = agentDef.configure(new AgentContext(request.agentName(), request.instanceId()));
            ModelProvider provider = providers.resolve(config.model());

            AgentSessionState current = store.loadSession(sessionId)
                    .orElseGet(() -> new AgentSessionState(
                            sessionId,
                            request.agentName(),
                            request.instanceId(),
                            request.sessionName(),
                            0,
                            List.of(),
                            start,
                            start));

            List<HelmMessage> messages = new ArrayList<>(current.messages());
            messages.add(HelmMessage.user(request.text()));
            messages = trimHistory(messages);
            String scopeId = memoryScopeId(request.agentName(), request.instanceId());
            List<Tool<?, ?>> tools = effectiveTools(config, scopeId);
            String instructions = instructionsWithMemories(config.instructions(), scopeId);
            List<ToolDescriptor> toolDescriptors =
                    tools.stream().map(ToolDescriptor::from).toList();
            AgentEngineResult result = engine.run(new AgentEngineRequest(
                    config.model(),
                    instructions,
                    toolDescriptors,
                    messages,
                    provider,
                    toolExecutor(tools, operationId, securityContext, config.sandbox()),
                    DEFAULT_TIMEOUT,
                    DEFAULT_MAX_TURNS,
                    buildEngineListener(operationId, eventSequence)));

            // Defer session save until the engine has produced output. On SessionConflict, reload + retry once.
            saveSessionWithRetry(sessionId, request, current, result.messages(), start);
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    HelmAction.PROMPT,
                    OperationStatus.SUCCEEDED,
                    JsonCodec.encodeRequest(request),
                    result.text(),
                    Map.of(),
                    start,
                    Instant.now()));
            appendEventSafely(
                    operationId,
                    null,
                    eventSequence.incrementAndGet(),
                    RuntimeEventType.OPERATION_SUCCEEDED,
                    Map.of("text", String.valueOf(result.text())));
            return new PromptExecution(operationId, result.text());
        } catch (RuntimeException e) {
            Map<String, Object> error = RuntimeErrorMapper.operationError(e);
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    HelmAction.PROMPT,
                    OperationStatus.FAILED,
                    JsonCodec.encodeRequest(request),
                    null,
                    error,
                    start,
                    Instant.now()));
            appendEventSafely(
                    operationId, null, eventSequence.incrementAndGet(), RuntimeEventType.OPERATION_FAILED, error);
            throw e;
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    /**
     * Saves the session with optimistic-concurrency retry. On {@link SessionConflictException}, reloads the current
     * state and retries the save once with the reloaded version. The engine's output messages are re-applied on top of
     * the reloaded state so concurrent version bumps (e.g. a workflow writing to the same session) do not silently lose
     * messages.
     */
    private void saveSessionWithRetry(
            String sessionId,
            AgentPromptRequest request,
            AgentSessionState loaded,
            List<HelmMessage> updatedMessages,
            Instant createdAt) {
        AgentSessionState base = loaded;
        List<HelmMessage> messages = updatedMessages;
        for (int attempt = 0; attempt <= MAX_SESSION_CONFLICT_RETRIES; attempt++) {
            try {
                AgentSessionState updated = new AgentSessionState(
                        sessionId,
                        request.agentName(),
                        request.instanceId(),
                        request.sessionName(),
                        base.version() + 1,
                        messages,
                        base.createdAt(),
                        Instant.now());
                store.saveSession(updated);
                return;
            } catch (SessionConflictException conflict) {
                if (attempt == MAX_SESSION_CONFLICT_RETRIES) {
                    throw conflict;
                }
                // Reload the latest persisted state and re-apply the engine's new messages on top of it. The engine's
                // output messages (assistant + tool results) follow the original input messages; we rebuild by taking
                // the reloaded history + user message + the engine's additions beyond the original input size.
                AgentSessionState reloaded = store.loadSession(sessionId).orElseThrow(() -> conflict);
                List<HelmMessage> reconciled = new ArrayList<>(reloaded.messages());
                reconciled.add(HelmMessage.user(request.text()));
                reconciled = trimHistory(reconciled);
                // Append engine-produced messages (everything after the original input the engine saw).
                int originalInputSize = loaded.messages().size() + 1; // +1 for the user message
                if (updatedMessages.size() > originalInputSize) {
                    reconciled.addAll(updatedMessages.subList(originalInputSize, updatedMessages.size()));
                }
                base = reloaded;
                messages = trimHistory(reconciled);
            }
        }
    }

    private AgentDefinition agent(String name) {
        AgentDefinition agentDef = agents.get(name);
        if (agentDef == null) {
            throw new AgentNotFoundException("No agent named " + name, Map.of("agent", name), Map.of());
        }
        return agentDef;
    }

    private ToolExecutor toolExecutor(
            List<Tool<?, ?>> tools, String operationId, HelmSecurityContext securityContext, Sandbox sandbox) {
        return (ignoredOperationId, name, input) ->
                executeTool(tools, operationId, name, input, securityContext, sandbox);
    }

    /** Combines agent-defined tools with the built-in memory tool when a memory store is configured. */
    private List<Tool<?, ?>> effectiveTools(AgentConfig config, String scopeId) {
        if (memoryStore == null) {
            return config.tools();
        }
        boolean hasMemoryTool = config.tools().stream().anyMatch(tool -> SaveMemoryTool.NAME.equals(tool.name()));
        if (hasMemoryTool) {
            return config.tools();
        }
        List<Tool<?, ?>> tools = new ArrayList<>(config.tools());
        tools.add(new SaveMemoryTool(memoryStore, scopeId));
        return List.copyOf(tools);
    }

    /** Appends recalled long-term memories to the agent instructions. */
    private String instructionsWithMemories(String instructions, String scopeId) {
        if (memoryStore == null) {
            return instructions;
        }
        List<MemoryRecord> memories = memoryStore.list(scopeId);
        if (memories.isEmpty()) {
            return instructions;
        }
        StringBuilder builder = new StringBuilder(instructions);
        builder.append("\n\nKnown long-term memories:");
        for (MemoryRecord memory : memories) {
            builder.append("\n- ");
            if (!memory.subject().isBlank()) {
                builder.append('[').append(memory.subject()).append("] ");
            }
            builder.append(memory.content());
        }
        return builder.toString();
    }

    /**
     * Keeps the conversation history bounded. Retains at most {@code maxSessionMessages} of the most recent messages,
     * dropping leading messages until the window starts at a user message so tool results are never orphaned.
     */
    private List<HelmMessage> trimHistory(List<HelmMessage> messages) {
        if (maxSessionMessages <= 0 || messages.size() <= maxSessionMessages) {
            return messages;
        }
        List<HelmMessage> window = messages.subList(messages.size() - maxSessionMessages, messages.size());
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).role() == Role.USER) {
                return new ArrayList<>(window.subList(i, window.size()));
            }
        }
        return new ArrayList<>(window);
    }

    private Object executeTool(
            List<Tool<?, ?>> tools,
            String operationId,
            String name,
            Object input,
            HelmSecurityContext securityContext,
            Sandbox sandbox) {
        Tool<?, ?> tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> ToolExecutionException.notFound(name));

        try {
            return executeUnchecked(tool, operationId, input, securityContext, sandbox);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolExecutionException(
                    "Tool execution failed",
                    Map.of("tool", name, "operationId", operationId, "message", messageOf(e)),
                    Map.of(),
                    e);
        } catch (Exception e) {
            throw new ToolExecutionException(
                    "Tool execution failed",
                    Map.of("tool", name, "operationId", operationId, "message", messageOf(e)),
                    Map.of(),
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private <I, O> O executeUnchecked(
            Tool<?, ?> tool, String operationId, Object input, HelmSecurityContext securityContext, Sandbox sandbox)
            throws Exception {
        Tool<I, O> typedTool = (Tool<I, O>) tool;
        return typedTool.execute(
                new ToolContext(operationId, securityContext, sandbox, Clock.systemUTC(), ToolLogger.noop()),
                (I) input);
    }

    private void appendEvent(
            String operationId,
            String workflowRunId,
            long sequence,
            RuntimeEventType type,
            Map<String, Object> payload) {
        store.appendEvent(new RuntimeEventRecord(
                "evt_" + UUID.randomUUID(),
                operationId,
                workflowRunId,
                sequence,
                type,
                EventRedactor.redact(payload),
                Instant.now()));
    }

    private void appendEventSafely(
            String operationId,
            String workflowRunId,
            long sequence,
            RuntimeEventType type,
            Map<String, Object> payload) {
        try {
            appendEvent(operationId, workflowRunId, sequence, type, payload);
        } catch (RuntimeException ignored) {
            // Event persistence must not change the operation outcome.
        }
    }

    /** Bridges {@link EngineEvent}s to {@link RuntimeEventRecord}s persisted in the store. */
    private EngineEventListener buildEngineListener(String operationId, AtomicInteger sequence) {
        return event -> {
            try {
                appendEvent(
                        operationId,
                        null,
                        sequence.incrementAndGet(),
                        engineEventType(event),
                        engineEventPayload(event));
            } catch (RuntimeException ignored) {
                // Engine event persistence must not change the operation outcome.
            }
        };
    }

    private static RuntimeEventType engineEventType(EngineEvent event) {
        return switch (event) {
            case EngineEvent.TurnStarted ignored -> RuntimeEventType.TURN_STARTED;
            case EngineEvent.TurnSucceeded ignored -> RuntimeEventType.TURN_SUCCEEDED;
            case EngineEvent.TurnFailed ignored -> RuntimeEventType.TURN_FAILED;
            case EngineEvent.ModelStarted ignored -> RuntimeEventType.MODEL_STARTED;
            case EngineEvent.ModelSucceeded ignored -> RuntimeEventType.MODEL_SUCCEEDED;
            case EngineEvent.ModelFailed ignored -> RuntimeEventType.MODEL_FAILED;
            case EngineEvent.ToolStarted ignored -> RuntimeEventType.TOOL_STARTED;
            case EngineEvent.ToolSucceeded ignored -> RuntimeEventType.TOOL_SUCCEEDED;
            case EngineEvent.ToolFailed ignored -> RuntimeEventType.TOOL_FAILED;
        };
    }

    private static Map<String, Object> engineEventPayload(EngineEvent event) {
        return switch (event) {
            case EngineEvent.TurnStarted t -> Map.of("turn", t.turnIndex());
            case EngineEvent.TurnSucceeded t -> Map.of(
                    "turn",
                    t.turnIndex(),
                    "inputTokens",
                    t.usage().inputTokens(),
                    "outputTokens",
                    t.usage().outputTokens());
            case EngineEvent.TurnFailed t -> Map.of("turn", t.turnIndex(), "code", t.code());
            case EngineEvent.ModelStarted m -> Map.of("turn", m.turnIndex());
            case EngineEvent.ModelSucceeded m -> Map.of(
                    "turn",
                    m.turnIndex(),
                    "inputTokens",
                    m.usage().inputTokens(),
                    "outputTokens",
                    m.usage().outputTokens());
            case EngineEvent.ModelFailed m -> Map.of(
                    "turn", m.turnIndex(), "code", m.code(), "message", String.valueOf(m.message()));
            case EngineEvent.ToolStarted t -> Map.of(
                    "turn", t.turnIndex(), "tool", t.toolName(), "toolCallId", t.toolCallId());
            case EngineEvent.ToolSucceeded t -> Map.of(
                    "turn", t.turnIndex(), "tool", t.toolName(), "toolCallId", t.toolCallId());
            case EngineEvent.ToolFailed t -> Map.of(
                    "turn",
                    t.turnIndex(),
                    "tool",
                    t.toolName(),
                    "toolCallId",
                    t.toolCallId(),
                    "code",
                    t.code(),
                    "message",
                    String.valueOf(t.message()));
        };
    }

    private void authorize(HelmSecurityContext context, HelmAction action, HelmResource resource) {
        AuthorizationResult result = authorizer.authorize(context, action, resource);
        if (!result.allowed()) {
            throw AuthorizationException.forbidden(
                    "Access denied: " + action,
                    Map.of("action", action.name(), "resource", resource.name(), "reason", result.reason()));
        }
    }

    private void acquireRate(RateLimitKey key) {
        AcquisitionResult result = rateLimiter.tryAcquire(key);
        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded",
                    Map.of("dimension", key.dimension(), "value", key.value(), "retryAfterMs", result.retryAfterMs()),
                    Map.of());
        }
    }

    private static void cancelSubscription(AtomicReference<Flow.Subscription> subscriptionRef) {
        Flow.Subscription current = subscriptionRef.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }

    private static String sessionId(String agentName, String instanceId, String sessionName) {
        return agentName + ":" + instanceId + ":" + sessionName;
    }

    private static String memoryScopeId(String agentName, String instanceId) {
        return agentName + ":" + instanceId;
    }

    private static String messageOf(Throwable throwable) {
        return String.valueOf(throwable.getMessage());
    }

    private record PromptExecution(String operationId, String text) {}

    /**
     * Closes the runtime: stops the lease manager (if any) and shuts down its internal scheduler. The user-provided
     * executor is NOT shut down (not owned by the runtime). Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (leaseManager != null) {
            leaseManager.close();
        }
    }

    public static final class Builder {
        private final List<AgentDefinition> agents = new ArrayList<>();
        private final List<ModelProvider> providers = new ArrayList<>();
        private RuntimeStore store = new InMemoryRuntimeStore();
        private MemoryStore memoryStore;
        private int maxSessionMessages;
        private HelmAuthorizer authorizer;
        private RateLimiter rateLimiter;
        private HelmSecurityContext defaultSecurityContext;
        private ExecutorService executor;
        private WorkQueue workQueue;

        public Builder agent(AgentDefinition agent) {
            agents.add(Objects.requireNonNull(agent, "agent"));
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

        /**
         * Enables long-term memory: recalled memories are injected into agent instructions and the built-in
         * {@code save_memory} tool is registered for every prompt.
         */
        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
            return this;
        }

        /**
         * Bounds the session history sent to the model and persisted after each prompt. {@code 0} (the default) keeps
         * the full history.
         */
        public Builder maxSessionMessages(int maxSessionMessages) {
            if (maxSessionMessages < 0) {
                throw new IllegalArgumentException("maxSessionMessages must not be negative");
            }
            this.maxSessionMessages = maxSessionMessages;
            return this;
        }

        /** Configures authorization; defaults to allow-all (dev). Production should supply a real authorizer. */
        public Builder authorizer(HelmAuthorizer authorizer) {
            this.authorizer = authorizer;
            return this;
        }

        /** Configures admission rate limiting; defaults to unlimited. */
        public Builder rateLimiter(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /** Default security context for requests that do not carry one (e.g. CLI). Defaults to anonymous. */
        public Builder defaultSecurityContext(HelmSecurityContext defaultSecurityContext) {
            this.defaultSecurityContext = defaultSecurityContext;
            return this;
        }

        /**
         * Enables durable async dispatch: operations are enqueued and executed on the executor. @Preview
         *
         * <p>Callers should supply a single-threaded executor (or serialize per-session dispatch) to avoid
         * {@link SessionBusyException} when multiple operations target the same session concurrently — the session lock
         * is held for the duration of each prompt, so a second dispatch for the same session will fail if the first is
         * still in-flight on another thread.
         */
        @io.agent.helm.core.annotation.Preview
        public Builder durable(ExecutorService executor, WorkQueue workQueue) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
            return this;
        }

        public AgentRuntime build() {
            return new AgentRuntime(
                    agents,
                    providers,
                    store,
                    memoryStore,
                    maxSessionMessages,
                    authorizer,
                    rateLimiter,
                    defaultSecurityContext,
                    executor,
                    workQueue);
        }
    }
}
