package io.agent.helm.examples.memorysession;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.memory.InMemoryMemoryStore;
import io.agent.helm.runtime.memory.SaveMemoryTool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * End-to-end validation of Helm's production capabilities on a support-assistant scenario: long-term memory across
 * sessions, persistent multi-turn sessions, typed tool calls, session management (list/inspect/reset), bounded history,
 * and operation inspection.
 */
final class MemorySessionExampleTest {

    @Test
    void supportAssistantScenarioValidatesMemorySessionsToolsAndOperations() {
        RecordingProvider provider = new RecordingProvider();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new SupportAgent())
                .provider(provider)
                .store(store)
                .memoryStore(memoryStore)
                .maxSessionMessages(20)
                .build();

        // --- Session 1: user states a durable preference; the model stores it via save_memory. ---
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested(
                        "call_1",
                        SaveMemoryTool.NAME,
                        new SaveMemoryTool.Input("shipping", "Customer prefers express shipping")),
                new ModelStreamEvent.Completed(new TokenUsage(10, 0)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Got it, I will remember that."),
                new ModelStreamEvent.Completed(new TokenUsage(12, 8)));

        PromptResult first = runtime.prompt(
                new AgentPromptRequest(SupportAgent.NAME, "customer-42", "monday", "I always want express shipping"));

        assertThat(first.text()).isEqualTo("Got it, I will remember that.");
        assertThat(memoryStore.list("support:customer-42"))
                .singleElement()
                .extracting(MemoryRecord::content)
                .isEqualTo("Customer prefers express shipping");

        // --- Session 1, turn 2: multi-turn session resumes persisted history and calls a tool. ---
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested("call_2", "order_status", new OrderStatusTool.Query("A-1001")),
                new ModelStreamEvent.Completed(new TokenUsage(15, 0)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Order A-1001 has shipped, arriving 2026-07-08."),
                new ModelStreamEvent.Completed(new TokenUsage(20, 10)));

        PromptResult second = runtime.prompt(
                new AgentPromptRequest(SupportAgent.NAME, "customer-42", "monday", "Where is order A-1001?"));

        assertThat(second.text()).contains("A-1001");
        AgentSessionState monday =
                runtime.getSession("support:customer-42:monday").orElseThrow();
        assertThat(monday.version()).isEqualTo(2);
        assertThat(monday.messages().size()).isGreaterThanOrEqualTo(4);

        // --- Session 2: a brand-new session recalls the long-term memory in its instructions. ---
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Welcome back! Express shipping as usual."),
                new ModelStreamEvent.Completed(new TokenUsage(8, 6)));

        runtime.prompt(new AgentPromptRequest(SupportAgent.NAME, "customer-42", "friday", "Hi, I need a new order"));

        ModelRequest fridayRequest = provider.requests.get(provider.requests.size() - 1);
        assertThat(fridayRequest.instructions())
                .contains("Known long-term memories:")
                .contains("Customer prefers express shipping");
        assertThat(fridayRequest.tools())
                .extracting(descriptor -> descriptor.name())
                .contains("order_status", SaveMemoryTool.NAME);

        // --- Session management: list, inspect, and reset sessions. ---
        assertThat(runtime.listSessions())
                .extracting(AgentSessionState::sessionName)
                .containsExactly("monday", "friday");

        runtime.resetSession("support:customer-42:monday");

        assertThat(runtime.getSession("support:customer-42:monday")).isEmpty();
        assertThat(runtime.listSessions())
                .extracting(AgentSessionState::sessionName)
                .containsExactly("friday");

        // Memory survives the session reset: it belongs to the agent instance, not the session.
        assertThat(memoryStore.list("support:customer-42")).hasSize(1);

        // --- Operations remain inspectable for every prompt. ---
        assertThat(runtime.listOperations()).hasSize(3).allSatisfy(operation -> assertThat(operation.status())
                .isEqualTo(OperationStatus.SUCCEEDED));
    }

    /** FakeProvider wrapper that records every {@link ModelRequest} for assertions. */
    private static final class RecordingProvider implements ModelProvider {
        private final FakeProvider delegate = new FakeProvider("fake");
        private final List<ModelRequest> requests = new ArrayList<>();

        void enqueue(ModelStreamEvent... events) {
            delegate.enqueue(events);
        }

        @Override
        public boolean supports(ModelRef model) {
            return delegate.supports(model);
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            requests.add(request);
            return delegate.stream(request);
        }
    }
}
