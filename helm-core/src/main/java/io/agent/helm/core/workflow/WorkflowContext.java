package io.agent.helm.core.workflow;

import io.agent.helm.core.agent.AgentHarnessApi;

public interface WorkflowContext<I> {
    I input();

    AgentHarnessApi harness();
}
