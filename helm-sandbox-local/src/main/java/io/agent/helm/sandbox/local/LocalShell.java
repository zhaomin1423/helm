package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.SandboxCommand;
import io.agent.helm.core.sandbox.SandboxCommandResult;
import io.agent.helm.core.sandbox.SandboxShell;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SandboxShell} that runs argv directly (no shell interpretation) with a fixed working directory (the sandbox
 * root), a timeout, and a captured-output limit.
 *
 * <h2>Security notes</h2>
 *
 * <p><b>Environment.</b> By default the subprocess inherits <em>nothing</em> from the parent process environment — an
 * empty {@code envAllowlist} means "no variables inherited". Only variables named in {@code envAllowlist} are copied
 * from the parent. Callers that need the full parent environment must opt in via {@code inheritParentEnv=true}. The
 * command's explicit {@link SandboxCommand#environment()} overlay is always applied on top of the inherited set.
 *
 * <p><b>Process tree.</b> On timeout or interrupt the entire descendant tree is forcibly killed (Java 21
 * {@link ProcessHandle#descendants()}) before the immediate process, so child processes are not orphaned.
 *
 * <p><b>Resource limits.</b> The only resource limits enforced are the wall-clock {@code timeout} and the per-stream
 * {@code outputLimitChars} cap. There is <em>no</em> CPU, memory (RSS), disk, or fork-count limit on the subprocess.
 * Callers that need hard resource isolation should wrap commands in {@code ulimit}/cgroups or use a containerized
 * sandbox. Enabling shell does not provide filesystem containment — see {@link LocalSandbox}.
 */
final class LocalShell implements SandboxShell {
    private final Path root;
    private final Duration defaultTimeout;
    private final int outputLimitChars;
    private final Set<String> envAllowlist;
    private final boolean inheritParentEnv;

    LocalShell(
            Path root,
            Duration defaultTimeout,
            int outputLimitChars,
            Set<String> envAllowlist,
            boolean inheritParentEnv) {
        this.root = root;
        this.defaultTimeout = defaultTimeout;
        this.outputLimitChars = outputLimitChars;
        this.envAllowlist = envAllowlist;
        this.inheritParentEnv = inheritParentEnv;
    }

    @Override
    public SandboxCommandResult execute(SandboxCommand command) {
        List<String> argv = command.argv();
        if (argv.isEmpty()) {
            throw new SandboxException("empty argv", Map.of(), Map.of());
        }
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile());
        Map<String, String> env = builder.environment();
        // SECURITY: default is "inherit nothing". An empty allowlist means no parent-env variables are
        // copied. inheritParentEnv is an explicit opt-in for full inheritance; when it is set the
        // allowlist (if non-empty) still filters the inherited set. The command's explicit environment
        // is always applied on top regardless.
        if (inheritParentEnv) {
            // ProcessBuilder pre-populates env with the parent env; keep it and optionally filter.
            if (!envAllowlist.isEmpty()) {
                env.keySet().retainAll(envAllowlist);
            }
        } else {
            // Inherit nothing; start from an empty env.
            env.clear();
            if (!envAllowlist.isEmpty()) {
                for (String name : envAllowlist) {
                    String value = System.getenv(name);
                    if (value != null) {
                        env.put(name, value);
                    }
                }
            }
        }
        env.putAll(command.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxException(
                    "failed to start command", Map.of("argv", argv), Map.of("message", String.valueOf(e.getMessage())));
        }

        // The sandbox timeout is a hard cap; a command may request a shorter timeout but not a longer one.
        Duration timeout = defaultTimeout;
        if (command.timeout() != null && command.timeout().compareTo(defaultTimeout) < 0) {
            timeout = command.timeout();
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean[] stdoutTruncated = {false};
        boolean[] stderrTruncated = {false};
        Thread outReader = Thread.startVirtualThread(() -> drain(process.getInputStream(), stdout, stdoutTruncated));
        Thread errReader = Thread.startVirtualThread(() -> drain(process.getErrorStream(), stderr, stderrTruncated));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new SandboxException("interrupted while awaiting command", Map.of("argv", argv), Map.of());
        }

        if (!finished) {
            killTree(process);
            joinQuietly(outReader);
            joinQuietly(errReader);
            return new SandboxCommandResult(
                    124,
                    capped(stdout, stdoutTruncated[0]),
                    capped(stderr, stderrTruncated[0]) + "[timed out after " + timeout + "]");
        }
        joinQuietly(outReader);
        joinQuietly(errReader);
        return new SandboxCommandResult(
                process.exitValue(), capped(stdout, stdoutTruncated[0]), capped(stderr, stderrTruncated[0]));
    }

    /**
     * Kills the entire process tree: first forcibly destroys every descendant, then the immediate process. Without
     * walking descendants, child processes (e.g. {@code sleep} under {@code sh}) are orphaned and keep running after
     * the parent is killed.
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void drain(InputStream in, StringBuilder sink, boolean[] truncated) {
        try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = outputLimitChars - sink.length();
                if (remaining > 0) {
                    int toAppend = Math.min(read, remaining);
                    sink.append(buffer, 0, toAppend);
                    if (read > toAppend) {
                        truncated[0] = true;
                    }
                } else if (read > 0) {
                    truncated[0] = true;
                }
            }
        } catch (IOException ignored) {
            // Stream closed when the process exits; ignore.
        }
    }

    private String capped(StringBuilder sink, boolean truncated) {
        String base = sink.toString();
        return truncated ? base + "[truncated]" : base;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
