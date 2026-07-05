package io.agent.helm.cli;

import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
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

    @Override
    public Integer call() {
        try {
            HelmApp app = loadApp();
            AgentPromptRequest request = new AgentPromptRequest(agent, instance, session, text);
            if (stream) {
                StringBuilder buf = new StringBuilder();
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                app.agentRuntime().promptStream(request).subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
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
                done.await(60, TimeUnit.SECONDS);
                System.out.print(buf);
                return failure.get() == null ? 0 : 1;
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
}
