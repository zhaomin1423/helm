package io.agent.helm.core.workflow;

import io.agent.helm.core.agent.AgentDefinition;

public record WorkflowConfig(AgentDefinition agent) {
    public static WorkflowConfig of(AgentDefinition agent) {
        return new WorkflowConfig(agent);
    }
}
