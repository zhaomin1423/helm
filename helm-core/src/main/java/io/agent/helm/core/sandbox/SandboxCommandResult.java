package io.agent.helm.core.sandbox;

public record SandboxCommandResult(int exitCode, String stdout, String stderr) {}
