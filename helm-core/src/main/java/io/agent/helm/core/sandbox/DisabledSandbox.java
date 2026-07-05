package io.agent.helm.core.sandbox;

import io.agent.helm.core.error.SandboxException;
import java.util.List;

/**
 * No-op sandbox used as the default when an agent does not configure one. Shell execution is disabled (raises
 * {@link SandboxException}); the file system is empty and read-only (writes/delete are no-ops, reads return empty,
 * existence checks return {@code false}).
 */
final class DisabledSandbox implements Sandbox, SandboxFileSystem, SandboxShell {
    static final DisabledSandbox INSTANCE = new DisabledSandbox();

    private DisabledSandbox() {}

    @Override
    public SandboxFileSystem fs() {
        return this;
    }

    @Override
    public SandboxShell shell() {
        return this;
    }

    @Override
    public SandboxCommandResult execute(SandboxCommand command) {
        throw new SandboxException(
                "shell execution is disabled in this sandbox",
                java.util.Map.of("argv", command.argv()),
                java.util.Map.of());
    }

    @Override
    public String readText(String path) {
        return "";
    }

    @Override
    public void writeText(String path, String content) {
        // no-op: read-only
    }

    @Override
    public List<String> listFiles(String path) {
        return List.of();
    }

    @Override
    public boolean exists(String path) {
        return false;
    }

    @Override
    public void delete(String path) {
        // no-op: read-only
    }
}
