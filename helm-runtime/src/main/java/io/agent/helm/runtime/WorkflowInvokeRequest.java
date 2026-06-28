package io.agent.helm.runtime;

public record WorkflowInvokeRequest<I>(String workflowName, I input) {}
