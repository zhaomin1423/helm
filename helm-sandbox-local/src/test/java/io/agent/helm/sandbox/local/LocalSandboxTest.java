package io.agent.helm.sandbox.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.SandboxCommand;
import io.agent.helm.core.sandbox.SandboxCommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalSandboxTest {
    @Test
    void shellDisabledByDefault(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder().root(tempDir).build();
        assertThatThrownBy(() -> sandbox.shell().execute(command(List.of("echo", "hi"))))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void shellRunsCommandWhenEnabled(@TempDir Path tempDir) {
        LocalSandbox sandbox =
                LocalSandbox.builder().root(tempDir).shellEnabled(true).build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("echo", "hi")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hi");
    }

    @Test
    void shellEnforcesTimeout(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .shellTimeout(Duration.ofMillis(100))
                .build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("sleep", "5")));

        assertThat(result.exitCode()).isEqualTo(124);
    }

    @Test
    void killTreeKillsDescendantsOnTimeout(@TempDir Path tempDir) throws Exception {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .shellTimeout(Duration.ofMillis(500))
                .build();
        // sh forks a background sleep and writes its PID to "pid" before waiting on it. Without
        // walking descendants, the sleep would be orphaned and keep running after sh is killed.
        SandboxCommandResult result = sandbox.shell()
                .execute(new SandboxCommand(
                        List.of("sh", "-c", "sleep 30 & echo $! > pid; wait"), Duration.ofSeconds(5), Map.of()));
        assertThat(result.exitCode()).isEqualTo(124);

        Path pidFile = tempDir.resolve("pid");
        assertThat(pidFile).exists();
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        // Give the OS a moment to reap the SIGKILLed descendant.
        Thread.sleep(1500);
        boolean alive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
        assertThat(alive).as("descendant process should have been killed").isFalse();
    }

    @Test
    void shellCapsOutput(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .outputLimitChars(5)
                .build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("printf", "abcdefghij")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).startsWith("abcde");
        assertThat(result.stdout()).endsWith("[truncated]");
    }

    @Test
    void shellInheritsNoEnvVarsByDefault(@TempDir Path tempDir) {
        LocalSandbox sandbox =
                LocalSandbox.builder().root(tempDir).shellEnabled(true).build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("env")));

        assertThat(result.exitCode()).isZero();
        // HOME is present in the parent env but must NOT leak to the subprocess with the default
        // (empty) allowlist.
        assertThat(result.stdout()).doesNotContain("HOME=");
    }

    @Test
    void shellInheritsAllowlistedEnvVar(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .envAllowlist(Set.of("HOME"))
                .build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("env")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("HOME=");
    }

    @Test
    void shellInheritsFullEnvWhenOptedIn(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .inheritParentEnv(true)
                .build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("env")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("HOME=");
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path tempDir) throws Exception {
        LocalSandbox sandbox = LocalSandbox.builder().root(tempDir).build();
        Path outside = Files.createTempDirectory("helm-outside");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("link"), outside);

        assertThatThrownBy(() -> sandbox.fs().readText("link/secret.txt")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsWriteThroughSymlinkToNonexistentTarget(@TempDir Path tempDir) throws Exception {
        LocalSandbox sandbox = LocalSandbox.builder().root(tempDir).build();
        Path nonExistentOutside = tempDir.getParent().resolve("helm-nonexistent-target");
        Files.deleteIfExists(nonExistentOutside);
        Files.createSymbolicLink(tempDir.resolve("link"), nonExistentOutside);

        assertThatThrownBy(() -> sandbox.fs().writeText("link/payload", "PWNED"))
                .isInstanceOf(SandboxException.class);
        assertThat(Files.exists(nonExistentOutside)).isFalse();
    }

    @Test
    void rejectsWriteToLeafSymlinkPointingOutsideRoot(@TempDir Path tempDir) throws Exception {
        LocalSandbox sandbox = LocalSandbox.builder().root(tempDir).build();
        Path outside = Files.createTempDirectory("helm-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");
        // Leaf symlink pointing directly at an outside file.
        Files.createSymbolicLink(tempDir.resolve("link"), outsideFile);

        assertThatThrownBy(() -> sandbox.fs().writeText("link", "PWNED")).isInstanceOf(SandboxException.class);
        assertThat(Files.readString(outsideFile)).isEqualTo("secret");
    }

    private static SandboxCommand command(List<String> argv) {
        return new SandboxCommand(argv, Duration.ofSeconds(5), Map.of());
    }
}
