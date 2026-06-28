package io.agent.helm.runtime;

public record AgentPromptRequest(String agentName, String instanceId, String sessionName, String text) {}
