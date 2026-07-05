package io.agent.helm.client;

/** Client-side request body for {@code POST /agents/{agent}/dispatch}. */
public record AgentDispatchRequest(String instance, String session, String text) {

    public static AgentDispatchRequest of(String instance, String session, String text) {
        return new AgentDispatchRequest(instance, session, text);
    }
}
