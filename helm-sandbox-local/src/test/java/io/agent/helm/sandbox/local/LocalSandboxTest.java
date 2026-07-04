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
    void shellCapsOutput(@TempDir Path tempDir) {
        LocalSandbox sandbox = LocalSandbox.builder()
                .root(tempDir)
                .shellEnabled(true)
                .outputLimitChars(5)
                .build();
        SandboxCommandResult result = sandbox.shell().execute(command(List.of("printf", "abcdefghij")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(5);
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path tempDir) throws Exception {
        LocalSandbox sandbox = LocalSandbox.builder().root(tempDir).build();
        Path outside = Files.createTempDirectory("helm-outside");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("link"), outside);

        assertThatThrownBy(() -> sandbox.fs().readText("link/secret.txt")).isInstanceOf(SandboxException.class);
    }

    private static SandboxCommand command(List<String> argv) {
        return new SandboxCommand(argv, Duration.ofSeconds(5), Map.of());
    }
}
