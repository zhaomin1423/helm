package io.agent.helm.core.sandbox;

public interface Sandbox {
    SandboxFileSystem fs();

    SandboxShell shell();
}
