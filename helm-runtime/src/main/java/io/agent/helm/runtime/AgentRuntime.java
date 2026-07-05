package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.error.AgentNotFoundException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.engine.AgentEngine;
import io.agent.helm.engine.AgentEngineRequest;
import io.agent.helm.engine.AgentEngineResult;
import io.agent.helm.engine.ToolExecutor;
import io.agent.helm.runtime.memory.SaveMemoryTool;
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

public final class AgentRuntime {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_TURNS = 8;

    private final Map<String, AgentDefinition> agents;
    private final ProviderRegistry providers;
    private final RuntimeStore store;
    private final MemoryStore memoryStore;
    private final int maxSessionMessages;
    private final AgentEngine engine = new AgentEngine();
    private final ConcurrentMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();

    private AgentRuntime(
            List<AgentDefinition> agents,
            List<ModelProvider> providers,
            RuntimeStore store,
            MemoryStore memoryStore,
            int maxSessionMessages) {
        Map<String, AgentDefinition> agentMap = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            agentMap.put(agent.name(), agent);
        }
        this.agents = Map.copyOf(agentMap);
        this.providers = new ProviderRegistry(providers);
        this.store = store;
        this.memoryStore = memoryStore;
        this.maxSessionMessages = maxSessionMessages;
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentHarness harness(String agentName, String instanceId) {
        return new AgentHarness(this, agentName, instanceId);
    }

    public PromptResult prompt(AgentPromptRequest request) {
        String operationId = "op_" + UUID.randomUUID();
        PromptExecution execution = executePrompt(request, operationId);
        return new PromptResult(execution.operationId(), execution.text());
    }

    public OperationHandle dispatch(AgentPromptRequest request) {
        String operationId = "op_" + UUID.randomUUID();
        try {
            executePrompt(request, operationId);
        } catch (RuntimeException e) {
            OperationStatus status = store.loadOperation(operationId)
                    .map(OperationRecord::status)
                    .orElse(OperationStatus.FAILED);
            return new OperationHandle(operationId, status);
        }
        return new OperationHandle(operationId, OperationStatus.SUCCEEDED);
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
        return store.listSessions();
    }

    /** Loads a persisted session (including its message history) by id. */
    public Optional<AgentSessionState> getSession(String sessionId) {
        return store.loadSession(sessionId);
    }

    /** Deletes a session and its history so the next prompt starts fresh. */
    public void resetSession(String sessionId) {
        if (activeSessions.containsKey(sessionId)) {
            throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
        }
        store.deleteSession(sessionId);
    }

    private PromptExecution executePrompt(AgentPromptRequest request, String operationId) {
        String sessionId = sessionId(request.agentName(), request.instanceId(), request.sessionName());
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
        }
        Instant now = Instant.now();

        try {
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    "PROMPT",
                    OperationStatus.RUNNING,
                    request.text(),
                    null,
                    Map.of(),
                    now,
                    null));
            appendEvent(
                    operationId,
                    null,
                    1,
                    RuntimeEventType.OPERATION_STARTED,
                    Map.of("text", String.valueOf(request.text())));

            AgentDefinition agent = agent(request.agentName());
            AgentConfig config = agent.configure(new AgentContext(request.agentName(), request.instanceId()));
            ModelProvider provider = providers.resolve(config.model());

            AgentSessionState current = store.loadSession(sessionId)
                    .orElseGet(() -> new AgentSessionState(
                            sessionId,
                            request.agentName(),
                            request.instanceId(),
                            request.sessionName(),
                            0,
                            List.of(),
                            now,
                            now));

            List<HelmMessage> messages = new ArrayList<>(current.messages());
            messages.add(HelmMessage.user(request.text()));
            messages = trimHistory(messages);
            AgentSessionState running = new AgentSessionState(
                    sessionId,
                    request.agentName(),
                    request.instanceId(),
                    request.sessionName(),
                    current.version() + 1,
                    messages,
                    current.createdAt(),
                    now);
            store.saveSession(running);

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
                    toolExecutor(tools, operationId),
                    DEFAULT_TIMEOUT,
                    DEFAULT_MAX_TURNS));

            AgentSessionState updated = new AgentSessionState(
                    sessionId,
                    request.agentName(),
                    request.instanceId(),
                    request.sessionName(),
                    running.version(),
                    result.messages(),
                    running.createdAt(),
                    Instant.now());
            store.saveSession(updated);
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    "PROMPT",
                    OperationStatus.SUCCEEDED,
                    request.text(),
                    result.text(),
                    Map.of(),
                    now,
                    Instant.now()));
            appendEventSafely(
                    operationId,
                    null,
                    2,
                    RuntimeEventType.OPERATION_SUCCEEDED,
                    Map.of("text", String.valueOf(result.text())));
            return new PromptExecution(operationId, result.text());
        } catch (RuntimeException e) {
            Map<String, Object> error = RuntimeErrorMapper.operationError(e);
            store.saveOperation(new OperationRecord(
                    operationId,
                    sessionId,
                    "PROMPT",
                    OperationStatus.FAILED,
                    request.text(),
                    null,
                    error,
                    now,
                    Instant.now()));
            appendEventSafely(operationId, null, 2, RuntimeEventType.OPERATION_FAILED, error);
            throw e;
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    private AgentDefinition agent(String name) {
        AgentDefinition agent = agents.get(name);
        if (agent == null) {
            throw new AgentNotFoundException("No agent named " + name, Map.of("agent", name), Map.of());
        }
        return agent;
    }

    private ToolExecutor toolExecutor(List<Tool<?, ?>> tools, String operationId) {
        return (ignoredOperationId, name, input) -> executeTool(tools, operationId, name, input);
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

    private Object executeTool(List<Tool<?, ?>> tools, String operationId, String name, Object input) {
        Tool<?, ?> tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ToolExecutionException(
                        "Tool not found", Map.of("tool", name, "operationId", operationId), Map.of()));

        try {
            return executeUnchecked(tool, operationId, input);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolExecutionException(
                    "Tool execution failed",
                    Map.of("tool", name, "operationId", operationId, "message", messageOf(e)),
                    Map.of());
        } catch (Exception e) {
            throw new ToolExecutionException(
                    "Tool execution failed",
                    Map.of("tool", name, "operationId", operationId, "message", messageOf(e)),
                    Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static <I, O> O executeUnchecked(Tool<?, ?> tool, String operationId, Object input) throws Exception {
        Tool<I, O> typedTool = (Tool<I, O>) tool;
        return typedTool.execute(new ToolContext(operationId), (I) input);
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
                type.type(),
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

    public static final class Builder {
        private final List<AgentDefinition> agents = new ArrayList<>();
        private final List<ModelProvider> providers = new ArrayList<>();
        private RuntimeStore store = new InMemoryRuntimeStore();
        private MemoryStore memoryStore;
        private int maxSessionMessages;

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

        public AgentRuntime build() {
            return new AgentRuntime(agents, providers, store, memoryStore, maxSessionMessages);
        }
    }
}
