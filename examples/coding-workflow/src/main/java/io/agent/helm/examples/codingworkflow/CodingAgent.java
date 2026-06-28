package io.agent.helm.examples.codingworkflow;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;

public final class CodingAgent implements AgentDefinition {
    @Override
    public String name() {
        return "coding-agent";
    }

    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
                .model("fake/coding-agent")
                .instructions(
                        """
                You are a careful software engineering agent.
                Work from the bound GitHub issue and repository context only.
                Produce a design before editing code.
                Treat failed verification and review findings as blockers.
                Never create a pull request directly; the workflow owns that step.
                """)
                .build();
    }
}
