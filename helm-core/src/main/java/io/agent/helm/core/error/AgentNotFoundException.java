package io.agent.helm.core.error;

import java.util.Map;

public final class AgentNotFoundException extends HelmException {
    public AgentNotFoundException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("AGENT_NOT_FOUND", message, details, developerDetails);
    }
}
