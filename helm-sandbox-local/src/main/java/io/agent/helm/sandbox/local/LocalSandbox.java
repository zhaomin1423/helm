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
 *
 * <h2>Security posture</h2>
 *
 * <p><b>Shell disabled (default).</b> When {@code shellEnabled} is {@code false} (the default), no subprocess can be
 * started and the sandbox provides filesystem-only isolation rooted at {@code root}.
 *
 * <p><b>Shell enabled.</b> Enabling shell runs commands with {@code root} as the working directory only — it does
 * <b>not</b> provide filesystem containment for the process. A shell-enabled command can {@code cd /}, read
 * {@code /etc/passwd} or {@code ~/.ssh/id_rsa} by absolute path, and open arbitrary network sockets. Shell should only
 * be enabled for trusted commands. For hard isolation on Linux, consider a future landlock/seccomp wrapper; it is not
 * implemented here.
 *
 * <p><b>Environment.</b> When shell is enabled, the subprocess inherits <em>no</em> parent-env variables by default
 * (empty {@code envAllowlist}). Use {@link Builder#envAllowlist(Set)} to allowlist specific variables (e.g.
 * {@code PATH}), or {@link Builder#inheritParentEnv(boolean)} to opt in to full parent-env inheritance.
 */
public final class LocalSandbox implements Sandbox {
    private final Path root;
    private final LocalFileSystem fileSystem;
    private final SandboxShell shell;

    private LocalSandbox(
            Path root,
            boolean shellEnabled,
            Duration shellTimeout,
            int outputLimitChars,
            Set<String> envAllowlist,
            boolean inheritParentEnv) {
        this.root = root;
        this.fileSystem = new LocalFileSystem(root);
        this.shell = shellEnabled
                ? new LocalShell(root, shellTimeout, outputLimitChars, envAllowlist, inheritParentEnv)
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
        private boolean inheritParentEnv = false;

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

        /**
         * Names the parent-env variables the subprocess may inherit. An <em>empty</em> allowlist (the default) means no
         * parent-env variables are inherited. Non-empty entries are copied from the parent env by name. Has no effect
         * when shell is disabled.
         */
        public Builder envAllowlist(Set<String> allowlist) {
            this.envAllowlist = allowlist;
            return this;
        }

        /**
         * Explicit opt-in to inherit the full parent process environment. When {@code true}, the subprocess starts with
         * the parent env (optionally filtered by {@link #envAllowlist(Set)} when non-empty). Default is {@code false} —
         * inherit nothing. Has no effect when shell is disabled.
         */
        public Builder inheritParentEnv(boolean inherit) {
            this.inheritParentEnv = inherit;
            return this;
        }

        public LocalSandbox build() {
            Objects.requireNonNull(root, "root");
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root must be an existing directory: " + root);
            }
            return new LocalSandbox(root, shellEnabled, shellTimeout, outputLimitChars, envAllowlist, inheritParentEnv);
        }
    }
}
