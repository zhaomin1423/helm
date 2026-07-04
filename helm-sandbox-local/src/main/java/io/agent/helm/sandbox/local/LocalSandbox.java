package io.agent.helm.sandbox.local;

import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxFileSystem;
import io.agent.helm.core.sandbox.SandboxShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link Sandbox} bound to a host root directory. All file paths are normalized and contained within the root; path
 * traversal, absolute paths, and symlink escapes are rejected. Shell execution is disabled by default and, when
 * enabled, runs argv directly with a fixed working directory, timeout, and output limit.
 */
public final class LocalSandbox implements Sandbox {
    private final Path root;
    private final LocalFileSystem fileSystem;
    private final SandboxShell shell;

    private LocalSandbox(
            Path root, boolean shellEnabled, Duration shellTimeout, int outputLimitChars, Set<String> envAllowlist) {
        this.root = root;
        this.fileSystem = new LocalFileSystem(root);
        this.shell = shellEnabled
                ? new LocalShell(root, shellTimeout, outputLimitChars, envAllowlist)
                : new DisabledShell("shell is disabled");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SandboxFileSystem fs() {
        return fileSystem;
    }

    @Override
    public SandboxShell shell() {
        return shell;
    }

    public Path root() {
        return root;
    }

    public static final class Builder {
        private Path root;
        private boolean shellEnabled = false;
        private Duration shellTimeout = Duration.ofSeconds(10);
        private int outputLimitChars = 1_000_000;
        private Set<String> envAllowlist = Set.of();

        public Builder root(Path root) {
            this.root = root;
            return this;
        }

        public Builder shellEnabled(boolean enabled) {
            this.shellEnabled = enabled;
            return this;
        }

        public Builder shellTimeout(Duration timeout) {
            this.shellTimeout = timeout;
            return this;
        }

        public Builder outputLimitChars(int chars) {
            this.outputLimitChars = chars;
            return this;
        }

        public Builder envAllowlist(Set<String> allowlist) {
            this.envAllowlist = allowlist;
            return this;
        }

        public LocalSandbox build() {
            Objects.requireNonNull(root, "root");
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root must be an existing directory: " + root);
            }
            return new LocalSandbox(root, shellEnabled, shellTimeout, outputLimitChars, envAllowlist);
        }
    }
}
