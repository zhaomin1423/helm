package io.agent.helm.core.sandbox;

/**
 * A controlled execution sandbox exposing a file system view and a shell. Implementations may be process-local,
 * containerized, or remote. Use {@link #disabled()} to obtain a no-op sandbox (shell disabled, empty read-only fs) for
 * agents that do not need sandboxing.
 */
public interface Sandbox {
    SandboxFileSystem fs();

    SandboxShell shell();

    /**
     * Returns a no-op sandbox: shell execution is disabled (raises {@link io.agent.helm.core.error.SandboxException})
     * and the file system is empty and read-only. Suitable as the default {@code sandbox} for an {@code AgentConfig}
     * that does not configure one.
     */
    static Sandbox disabled() {
        return DisabledSandbox.INSTANCE;
    }
}
