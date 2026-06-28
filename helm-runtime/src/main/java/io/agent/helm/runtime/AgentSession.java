package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentSessionApi;
import io.agent.helm.core.agent.PromptResult;

final class AgentSession implements AgentSessionApi {
    private final AgentRuntime runtime;
    private final String agentName;
    private final String instanceId;
    private final String sessionName;

    AgentSession(AgentRuntime runtime, String agentName, String instanceId, String sessionName) {
        this.runtime = runtime;
        this.agentName = agentName;
        this.instanceId = instanceId;
        this.sessionName = sessionName;
    }

    @Override
    public PromptResult prompt(String text) {
        return runtime.prompt(new AgentPromptRequest(agentName, instanceId, sessionName, text));
    }
}
