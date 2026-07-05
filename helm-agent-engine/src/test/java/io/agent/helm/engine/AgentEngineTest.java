package io.agent.helm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.MaxTurnsExceededException;
import io.agent.helm.core.error.TurnTimeoutException;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

final class AgentEngineTest {
    @Test
    void returnsTerminalAssistantText() {
        AgentEngine engine = new AgentEngine();
        AgentEngineResult result = engine.run(new AgentEngineRequest(
                ModelRef.parse("fake/test"),
                "Be helpful.",
                List.of(),
                List.of(HelmMessage.user("hello")),
                new ScriptedProvider(List.of(
                        new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)))),
                ToolExecutor.none(),
                Duration.ofSeconds(5),
                4));

        assertThat(result.text()).isEqualTo("hi");
        assertThat(result.messages()).last().satisfies(message -> assertThat(message)
                .isEqualTo(HelmMessage.assistant("hi")));
    }

    @Test
    void executesToolCallBeforeFinalAssistantText() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(
                        new ModelStreamEvent.ToolCallRequested("call_1", "echo", "input"),
                        new ModelStreamEvent.Completed(new TokenUsage(1, 0))),
                List.of(
                        new ModelStreamEvent.ContentDelta("tool said output"),
                        new ModelStreamEvent.Completed(new TokenUsage(1, 3))));
        ToolExecutor executor = (operationId, name, input) -> name.equals("echo") ? "output" : "unexpected";

        AgentEngineResult result = new AgentEngine()
                .run(new AgentEngineRequest(
                        ModelRef.parse("fake/test"),
                        "Use tools.",
                        List.of(),
                        List.of(HelmMessage.user("run echo")),
                        provider,
                        executor,
                        Duration.ofSeconds(5),
                        4));

        assertThat(result.text()).isEqualTo("tool said output");
        assertThat(provider.calls()).isEqualTo(2);
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(1).messages())
                .extracting(HelmMessage::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL);
        assertThat(provider.requests().get(1).messages().get(1).content())
                .singleElement()
                .isInstanceOfSatisfying(ToolCallBlock.class, toolCall -> {
                    assertThat(toolCall.id()).isEqualTo("call_1");
                    assertThat(toolCall.name()).isEqualTo("echo");
                    assertThat(toolCall.input()).isEqualTo("input");
                });
        assertThat(provider.requests().get(1).messages().get(2).content())
                .singleElement()
                .isInstanceOfSatisfying(ToolResultBlock.class, toolResult -> {
                    assertThat(toolResult.toolCallId()).isEqualTo("call_1");
                    assertThat(toolResult.output()).isEqualTo("output");
                    assertThat(toolResult.error()).isFalse();
                });
    }

    @Test
    void cancelsActiveSubscriptionOnTimeout() {
        HangingProvider provider = new HangingProvider();

        assertThatThrownBy(() -> new TurnRunner()
                        .run(
                                provider,
                                new ModelRequest(
                                        ModelRef.parse("fake/test"),
                                        "Wait.",
                                        List.of(),
                                        List.of(HelmMessage.user("hang")),
                                        Duration.ofMillis(10))))
                .isInstanceOf(TurnTimeoutException.class)
                .hasMessageContaining("Model stream timed out");

        assertThat(provider.subscription().cancelled()).isTrue();
    }

    @Test
    void ignoresLateEventsAfterCancellation() {
        LateEventAfterCancelProvider provider = new LateEventAfterCancelProvider();

        assertThatThrownBy(() -> new TurnRunner()
                        .run(
                                provider,
                                new ModelRequest(
                                        ModelRef.parse("fake/test"),
                                        "Wait.",
                                        List.of(),
                                        List.of(HelmMessage.user("hang")),
                                        Duration.ofMillis(10))))
                .isInstanceOf(TurnTimeoutException.class)
                .hasMessageContaining("Model stream timed out");

        assertThat(provider.subscription().cancelled()).isTrue();
    }

    @Test
    void throwsWhenMaxTurnsExceeded() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new ModelStreamEvent.ToolCallRequested("call_1", "echo", "input"),
                new ModelStreamEvent.Completed(new TokenUsage(1, 0))));

        assertThatThrownBy(() -> new AgentEngine()
                        .run(new AgentEngineRequest(
                                ModelRef.parse("fake/test"),
                                "Use tools.",
                                List.of(),
                                List.of(HelmMessage.user("run echo")),
                                provider,
                                (operationId, name, input) -> "output",
                                Duration.ofSeconds(5),
                                1)))
                .isInstanceOf(MaxTurnsExceededException.class)
                .hasMessageContaining("Agent loop exceeded max turns");
    }

    private static final class ScriptedProvider implements ModelProvider {
        private final List<List<ModelStreamEvent>> scripts;
        private final List<ModelRequest> requests = new java.util.ArrayList<>();
        private int calls;

        @SafeVarargs
        private ScriptedProvider(List<ModelStreamEvent>... scripts) {
            this.scripts = List.of(scripts);
        }

        @Override
        public boolean supports(ModelRef model) {
            return true;
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            requests.add(request);
            List<ModelStreamEvent> script = scripts.get(calls++);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private int index;
                private long demand;
                private boolean completed;
                private boolean emitting;

                @Override
                public synchronized void request(long n) {
                    if (completed || n <= 0) {
                        return;
                    }
                    demand += n;
                    if (emitting) {
                        return;
                    }
                    emitting = true;
                    try {
                        while (demand > 0 && index < script.size()) {
                            demand--;
                            subscriber.onNext(script.get(index++));
                        }
                        if (!completed && index == script.size()) {
                            completed = true;
                            subscriber.onComplete();
                        }
                    } finally {
                        emitting = false;
                    }
                }

                @Override
                public void cancel() {
                    completed = true;
                }
            });
        }

        private int calls() {
            return calls;
        }

        private List<ModelRequest> requests() {
            return requests;
        }
    }

    private static final class HangingProvider implements ModelProvider {
        private final HangingSubscription subscription = new HangingSubscription();

        @Override
        public boolean supports(ModelRef model) {
            return true;
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            return subscriber -> subscriber.onSubscribe(subscription);
        }

        private HangingSubscription subscription() {
            return subscription;
        }
    }

    private static final class LateEventAfterCancelProvider implements ModelProvider {
        private final LateEventAfterCancelSubscription subscription = new LateEventAfterCancelSubscription();

        @Override
        public boolean supports(ModelRef model) {
            return true;
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            return subscriber -> {
                subscription.subscriber = subscriber;
                subscriber.onSubscribe(subscription);
            };
        }

        private LateEventAfterCancelSubscription subscription() {
            return subscription;
        }
    }

    private static final class LateEventAfterCancelSubscription implements Flow.Subscription {
        private volatile Flow.Subscriber<? super ModelStreamEvent> subscriber;
        private boolean cancelled;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {
            cancelled = true;
            subscriber.onNext(new ModelStreamEvent.ContentDelta("late"));
            subscriber.onComplete();
        }

        private boolean cancelled() {
            return cancelled;
        }
    }

    private static final class HangingSubscription implements Flow.Subscription {
        private boolean cancelled;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {
            cancelled = true;
        }

        private boolean cancelled() {
            return cancelled;
        }
    }
}
