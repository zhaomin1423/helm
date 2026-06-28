package io.agent.helm.core.store;

import io.agent.helm.core.message.HelmMessage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentSessionState(
        String id,
        String agentName,
        String instanceId,
        String sessionName,
        long version,
        List<HelmMessage> messages,
        Instant createdAt,
        Instant updatedAt) {
    public AgentSessionState {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }
}
