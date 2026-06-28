package io.agent.helm.core.sandbox;

public interface SandboxShell {
    SandboxCommandResult execute(SandboxCommand command);
}
