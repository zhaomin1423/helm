package io.agent.helm.runtime;

public record WorkflowRunHandle<O>(String runId, O result) {}
