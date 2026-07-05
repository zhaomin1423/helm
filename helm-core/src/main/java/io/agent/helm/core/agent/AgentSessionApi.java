package io.agent.helm.core.agent;

import io.agent.helm.core.annotation.Preview;
import java.util.concurrent.Flow;

public interface AgentSessionApi {
    PromptResult prompt(String text);

    /**
     * Streams incremental prompt events as the model produces them. {@code @Preview} the streaming surface is being
     * validated; the current runtime emits events after the operation completes, with true incremental token streaming
     * planned.
     */
    @Preview
    Flow.Publisher<PromptStreamEvent> promptStream(String text);
}
