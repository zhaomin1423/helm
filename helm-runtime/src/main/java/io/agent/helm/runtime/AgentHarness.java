package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentHarnessApi;
import io.agent.helm.core.agent.AgentSessionApi;

final class AgentHarness implements AgentHarnessApi {
    private final AgentRuntime runtime;
    private final String agentName;
    private final String instanceId;

    AgentHarness(AgentRuntime runtime, String agentName, String instanceId) {
        this.runtime = runtime;
        this.agentName = agentName;
        this.instanceId = instanceId;
    }

    @Override
    public AgentSessionApi session(String sessionName) {
        return new AgentSession(runtime, agentName, instanceId, sessionName);
    }
}
