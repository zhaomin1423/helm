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
 */
final class LocalShell implements SandboxShell {
    private final Path root;
    private final Duration defaultTimeout;
    private final int outputLimitChars;
    private final Set<String> envAllowlist;

    LocalShell(Path root, Duration defaultTimeout, int outputLimitChars, Set<String> envAllowlist) {
        this.root = root;
        this.defaultTimeout = defaultTimeout;
        this.outputLimitChars = outputLimitChars;
        this.envAllowlist = envAllowlist;
    }

    @Override
    public SandboxCommandResult execute(SandboxCommand command) {
        List<String> argv = command.argv();
        if (argv.isEmpty()) {
            throw new SandboxException("empty argv", Map.of(), Map.of());
        }
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile());
        Map<String, String> env = builder.environment();
        // The allowlist filters only the inherited environment; the command's explicit environment
        // is always applied on top. An empty allowlist means no filtering (inherit everything).
        if (!envAllowlist.isEmpty()) {
            env.keySet().retainAll(envAllowlist);
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
        Thread outReader = Thread.startVirtualThread(() -> drain(process.getInputStream(), stdout));
        Thread errReader = Thread.startVirtualThread(() -> drain(process.getErrorStream(), stderr));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxException("interrupted while awaiting command", Map.of("argv", argv), Map.of());
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(outReader);
            joinQuietly(errReader);
            return new SandboxCommandResult(124, capped(stdout), capped(stderr) + "[timed out after " + timeout + "]");
        }
        joinQuietly(outReader);
        joinQuietly(errReader);
        return new SandboxCommandResult(process.exitValue(), capped(stdout), capped(stderr));
    }

    private void drain(InputStream in, StringBuilder sink) {
        try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = outputLimitChars - sink.length();
                if (remaining > 0) {
                    sink.append(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException ignored) {
            // Stream closed when the process exits; ignore.
        }
    }

    private String capped(StringBuilder sink) {
        if (sink.length() <= outputLimitChars) {
            return sink.toString();
        }
        return sink.substring(0, outputLimitChars) + "[truncated]";
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
