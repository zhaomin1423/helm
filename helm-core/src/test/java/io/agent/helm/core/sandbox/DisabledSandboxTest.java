package io.agent.helm.core.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.SandboxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DisabledSandboxTest {
    @Test
    void disabledIsReusableSingleton() {
        assertThat(Sandbox.disabled()).isSameAs(Sandbox.disabled());
    }

    @Test
    void shellExecutionThrowsSandboxException() {
        SandboxCommand command = new SandboxCommand(List.of("echo", "hi"), Duration.ofSeconds(1), Map.of());

        assertThatThrownBy(() -> Sandbox.disabled().shell().execute(command))
                .isInstanceOf(SandboxException.class)
                .satisfies(ex -> assertThat(((SandboxException) ex).details()).containsKey("argv"));
    }

    @Test
    void fileSystemIsEmptyAndReadOnly() {
        SandboxFileSystem fs = Sandbox.disabled().fs();

        assertThat(fs.exists("anything")).isFalse();
        assertThat(fs.listFiles("any/dir")).isEmpty();
        assertThat(fs.readText("missing.txt")).isEmpty();

        // writes / deletes are no-ops and must not throw
        fs.writeText("path.txt", "content");
        fs.delete("path.txt");
        assertThat(fs.exists("path.txt")).isFalse();
    }
}
