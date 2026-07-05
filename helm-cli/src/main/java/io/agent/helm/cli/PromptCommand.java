package io.agent.helm.cli;

import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.error.EngineInterruptedException;
import io.agent.helm.core.error.TurnTimeoutException;
import io.agent.helm.runtime.AgentPromptRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "prompt",
        description = "Send a prompt to an agent and print the result; use --stream to receive events as they arrive.")
final class PromptCommand extends HelmSubcommand {

    @Parameters(index = "0", description = "Agent name.")
    String agent;

    @Parameters(index = "1", description = "Instance id.")
    String instance;

    @Parameters(index = "2", description = "Session name.")
    String session;

    @Parameters(index = "3", description = "Prompt text.")
    String text;

    @Option(names = "--stream", description = "Stream prompt events as Server-Sent-Events-like data frames.")
    boolean stream;

    /** Stream timeout in seconds; on expiry the subscription is cancelled and the command exits 1. */
    static final long STREAM_TIMEOUT_SECONDS = 60;

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            AgentPromptRequest request = new AgentPromptRequest(agent, instance, session, text);
            if (stream) {
                return runStream(app, request);
            }
            PromptResult result = app.agentRuntime().prompt(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationId", result.operationId());
            body.put("text", result.text());
            System.out.println(toJson(body));
            return 0;
        } catch (Throwable e) {
            System.err.println(toJson(HelmApps.errorBody(e)));
            return 1;
        }
    }

    private int runStream(HelmApp app, AgentPromptRequest request) throws InterruptedException {
        StringBuilder buf = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
        app.agentRuntime().promptStream(request).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(PromptStreamEvent event) {
                buf.append("data: ").append(toJson(event)).append("\n\n");
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
        boolean completed;
        try {
            completed = done.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so callers up the stack see it, then report a structured error.
            Thread.currentThread().interrupt();
            cancelSubscription(subscriptionRef);
            System.err.println(toJson(HelmApps.errorBody(new EngineInterruptedException(
                    "Interrupted while streaming prompt",
                    Map.of("timeoutSeconds", STREAM_TIMEOUT_SECONDS),
                    Map.of()))));
            return 1;
        }
        // Always cancel the subscription on exit (success, timeout, or interruption) so a slow publisher cannot keep
        // producing into a dead subscriber.
        cancelSubscription(subscriptionRef);
        Throwable failureCause = failure.get();
        if (failureCause != null) {
            System.err.println(toJson(HelmApps.errorBody(failureCause)));
            return 1;
        }
        if (!completed) {
            // Timeout: print a structured error to stderr and exit non-zero instead of silently returning 0.
            System.err.println(toJson(HelmApps.errorBody(new TurnTimeoutException(
                    "Prompt stream timed out after " + STREAM_TIMEOUT_SECONDS + "s",
                    Map.of("timeoutSeconds", STREAM_TIMEOUT_SECONDS),
                    Map.of()))));
            return 1;
        }
        System.out.print(buf);
        return 0;
    }

    private static void cancelSubscription(AtomicReference<Flow.Subscription> subscriptionRef) {
        Flow.Subscription current = subscriptionRef.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }
}
