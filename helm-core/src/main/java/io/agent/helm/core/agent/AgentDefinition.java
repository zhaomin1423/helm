package io.agent.helm.core.agent;

public interface AgentDefinition {
    String name();

    AgentConfig configure(AgentContext context);
}
