package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.runtime.memory.InMemoryMemoryStore;
import io.agent.helm.runtime.memory.SaveMemoryTool;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Covers long-term memory integration, session management APIs, and history trimming. */
final class AgentRuntimeMemorySessionTest {

    @Test
    void memoryToolIsAdvertisedAndSavesMemoryWhenModelCallsIt() {
        RecordingProvider provider = new RecordingProvider();
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested(
                        "call_1", SaveMemoryTool.NAME, new SaveMemoryTool.Input("language", "User prefers Java")),
                new ModelStreamEvent.Completed(new TokenUsage(1, 0)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Noted."), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .memoryStore(memoryStore)
                .build();

        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "s1", "Remember I prefer Java"));

        assertThat(provider.requests.get(0).tools())
                .anySatisfy(tool -> assertThat(tool.name()).isEqualTo(SaveMemoryTool.NAME));
        assertThat(memoryStore.list("assistant:instance-1"))
                .singleElement()
                .satisfies(memory -> {
                    assertThat(memory.subject()).isEqualTo("language");
                    assertThat(memory.content()).isEqualTo("User prefers Java");
                });
    }

    @Test
    void memoriesAreInjectedIntoInstructionsOfLaterSessions() {
        RecordingProvider provider = new RecordingProvider();
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("Hi again"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        memoryStore.save(new MemoryRecord(
                "m1", "assistant:instance-1", "language", "User prefers Java", Instant.ofEpochSecond(1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .memoryStore(memoryStore)
                .build();

        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "new-session", "Hello"));

        assertThat(provider.requests.get(0).instructions())
                .contains("Known long-term memories:")
                .contains("[language] User prefers Java");
    }

    @Test
    void withoutMemoryStoreNoMemoryToolIsAdvertised() {
        RecordingProvider provider = new RecordingProvider();
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .build();

        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "s1", "Hi"));

        assertThat(provider.requests.get(0).tools()).isEmpty();
        assertThat(provider.requests.get(0).instructions()).doesNotContain("Known long-term memories:");
    }

    @Test
    void listGetAndResetSessionManageSessionLifecycle() {
        RecordingProvider provider = new RecordingProvider();
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("one"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("two"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .build();

        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "a", "Hi"));
        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "b", "Hi"));

        assertThat(runtime.listSessions())
                .extracting(AgentSessionState::sessionName)
                .containsExactly("a", "b");
        assertThat(runtime.getSession("assistant:instance-1:a")).isPresent();

        runtime.resetSession("assistant:instance-1:a");

        assertThat(runtime.getSession("assistant:instance-1:a")).isEmpty();
        assertThat(runtime.listSessions()).extracting(AgentSessionState::sessionName).containsExactly("b");
    }

    @Test
    void maxSessionMessagesTrimsHistoryStartingAtUserMessage() {
        RecordingProvider provider = new RecordingProvider();
        for (int i = 0; i < 5; i++) {
            provider.enqueue(
                    new ModelStreamEvent.ContentDelta("answer-" + i),
                    new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        }
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .maxSessionMessages(4)
                .build();

        for (int i = 0; i < 5; i++) {
            runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "long", "question-" + i));
        }

        ModelRequest lastRequest = provider.requests.get(provider.requests.size() - 1);
        assertThat(lastRequest.messages().size()).isLessThanOrEqualTo(4);
        assertThat(lastRequest.messages().get(0).role()).isEqualTo(Role.USER);
        AgentSessionState session =
                runtime.getSession("assistant:instance-1:long").orElseThrow();
        assertThat(session.messages().size()).isLessThanOrEqualTo(5);
    }

    private static final class AssistantAgent implements AgentDefinition {
        @Override
        public String name() {
            return "assistant";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("You are helpful.")
                    .build();
        }
    }

    /** FakeProvider that also records each {@link ModelRequest} it receives. */
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
