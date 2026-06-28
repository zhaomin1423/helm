package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.error.ProviderNotFoundException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.TypeDescriptor;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentRuntimeTest {
    @Test
    void promptReturnsFakeProviderTextAndPersistsSession() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .store(store)
                .build();

        PromptResult result = runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi"));

        assertThat(result.text()).isEqualTo("hello");
        assertThat(store.loadSession("assistant:instance-1:default")).isPresent();
        assertThat(store.loadOperation(result.operationId())).isPresent();
    }

    @Test
    void promptResumesExistingSessionMessages() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("first"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("second"), new ModelStreamEvent.Completed(new TokenUsage(2, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .store(store)
                .build();

        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi"));
        runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Again"));

        var session = store.loadSession("assistant:instance-1:default").orElseThrow();
        assertThat(session.version()).isEqualTo(2);
        assertThat(session.messages()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void promptExecutesRegisteredTool() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested("call_1", "echo", "input"),
                new ModelStreamEvent.Completed(new TokenUsage(1, 0)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("tool said output"),
                new ModelStreamEvent.Completed(new TokenUsage(1, 3)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new ToolAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();

        PromptResult result =
                runtime.prompt(new AgentPromptRequest("tool-agent", "instance-1", "default", "Use the tool"));

        assertThat(result.text()).isEqualTo("tool said output");
    }

    @Test
    void releasesActiveSessionAfterFailedToolPromptAndAllowsRetry() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested("call_1", "flaky", "input"),
                new ModelStreamEvent.Completed(new TokenUsage(1, 0)));
        provider.enqueue(
                new ModelStreamEvent.ContentDelta("retry ok"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new FlakyToolAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();

        assertThatThrownBy(() ->
                        runtime.prompt(new AgentPromptRequest("tool-agent", "instance-1", "default", "Use the tool")))
                .isInstanceOf(ToolExecutionException.class);

        PromptResult retry = runtime.prompt(new AgentPromptRequest("tool-agent", "instance-1", "default", "Retry"));

        assertThat(retry.text()).isEqualTo("retry ok");
    }

    @Test
    void persistsHelmExceptionDetailsForProviderFailureAndRecordsFailedOperation() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime =
                AgentRuntime.builder().agent(new AssistantAgent()).store(store).build();

        assertThatThrownBy(() -> runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi")))
                .isInstanceOf(ProviderNotFoundException.class);

        OperationRecord operation = store.operations().getFirst();
        assertThat(operation.status()).isEqualTo("FAILED");
        assertThat(operation.error().keySet()).containsExactlyInAnyOrder("code", "details", "exception", "message");
        assertThat(operation.error()).containsEntry("code", "PROVIDER_NOT_FOUND");
        assertThat(operation.error()).containsEntry("exception", ProviderNotFoundException.class.getName());
        assertThat(operation.error()).containsEntry("details", Map.of("model", "fake/test"));
    }

    @Test
    void rejectsConcurrentPromptForSameSession() throws Exception {
        BlockingProvider provider = new BlockingProvider("fake");
        AgentRuntime runtime = AgentRuntime.builder()
                .agent(new AssistantAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();

        Thread first = Thread.startVirtualThread(
                () -> runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi")));
        provider.awaitRequest();

        assertThatThrownBy(() -> runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Again")))
                .isInstanceOf(SessionBusyException.class)
                .hasMessageContaining("Session is busy");

        provider.complete();
        first.join();
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

    private static final class ToolAgent implements AgentDefinition {
        @Override
        public String name() {
            return "tool-agent";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Use tools.")
                    .tool(new EchoTool())
                    .build();
        }
    }

    private static final class FlakyToolAgent implements AgentDefinition {
        @Override
        public String name() {
            return "tool-agent";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Use tools.")
                    .tool(new FlakyTool())
                    .build();
        }
    }

    private static final class EchoTool implements Tool<String, String> {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public TypeDescriptor<String> inputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String execute(ToolContext context, String input) {
            return "output";
        }
    }

    private static final class FlakyTool implements Tool<String, String> {
        private static final AtomicInteger INVOCATIONS = new AtomicInteger();

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public TypeDescriptor<String> inputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String execute(ToolContext context, String input) {
            if (INVOCATIONS.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }
            return "ignored";
        }
    }

    private static final class BlockingProvider implements io.agent.helm.core.model.ModelProvider {
        private final String providerId;
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public boolean supports(io.agent.helm.core.model.ModelRef model) {
            return providerId.equals(model.providerId());
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(io.agent.helm.core.model.ModelRequest request) {
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            Thread.startVirtualThread(() -> {
                requested.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                publisher.submit(new ModelStreamEvent.ContentDelta("done"));
                publisher.submit(new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
                publisher.close();
            });
            return publisher;
        }

        private void awaitRequest() throws InterruptedException {
            requested.await();
        }

        private void complete() {
            release.countDown();
        }
    }
}
