package io.agent.helm.examples.memorysession;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;

/**
 * Customer-support assistant used to validate production framework capabilities: long-term memory, persistent sessions,
 * session management, bounded history, and typed tools.
 */
public final class SupportAgent implements AgentDefinition {
    public static final String NAME = "support";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
                .model("fake/support")
                .instructions("You are a customer support assistant. Use save_memory to store durable "
                        + "user preferences and the order_status tool to look up orders.")
                .tool(new OrderStatusTool())
                .build();
    }
}
