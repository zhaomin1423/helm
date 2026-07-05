package io.agent.helm.core.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.SandboxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract tests for {@link Sandbox}. Sandbox adapters extend this class and implement
 * {@link #createSandbox()} to return a fresh, isolated sandbox per test.
 */
public abstract class SandboxContractTest {
    private Sandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = createSandbox();
    }

    protected abstract Sandbox createSandbox();

    protected Sandbox sandbox() {
        return sandbox;
    }

    @Test
    void writeThenReadTextRoundTrips() {
        sandbox().fs().writeText("hello.txt", "hello");
        assertThat(sandbox().fs().readText("hello.txt")).isEqualTo("hello");
    }

    @Test
    void existsReflectsWritesAndDeletes() {
        assertThat(sandbox().fs().exists("missing.txt")).isFalse();
        sandbox().fs().writeText("present.txt", "x");
        assertThat(sandbox().fs().exists("present.txt")).isTrue();
        sandbox().fs().delete("present.txt");
        assertThat(sandbox().fs().exists("present.txt")).isFalse();
    }

    @Test
    void listFilesReturnsWrittenFiles() {
        sandbox().fs().writeText("a.txt", "a");
        sandbox().fs().writeText("b.txt", "b");
        assertThat(sandbox().fs().listFiles("")).containsExactlyInAnyOrder("a.txt", "b.txt");
    }

    @Test
    void listFilesListsSubdirectoryContents() {
        sandbox().fs().writeText("sub/c.txt", "c");
        sandbox().fs().writeText("sub/d.txt", "d");
        assertThat(sandbox().fs().listFiles("sub")).containsExactlyInAnyOrder("c.txt", "d.txt");
    }

    @Test
    void deleteRemovesFile() {
        sandbox().fs().writeText("gone.txt", "x");
        sandbox().fs().delete("gone.txt");
        assertThat(sandbox().fs().exists("gone.txt")).isFalse();
    }

    @Test
    void deleteRemovesDirectoryRecursively() {
        sandbox().fs().writeText("dir/a.txt", "a");
        sandbox().fs().writeText("dir/sub/b.txt", "b");
        sandbox().fs().delete("dir");
        assertThat(sandbox().fs().exists("dir")).isFalse();
        assertThat(sandbox().fs().exists("dir/a.txt")).isFalse();
        assertThat(sandbox().fs().exists("dir/sub/b.txt")).isFalse();
    }

    @Test
    void readTextOfMissingFileThrows() {
        assertThatThrownBy(() -> sandbox().fs().readText("nope.txt")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsReadPathTraversal() {
        assertThatThrownBy(() -> sandbox().fs().readText("../escape")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsWritePathTraversal() {
        assertThatThrownBy(() -> sandbox().fs().writeText("../escape", "x")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> sandbox().fs().readText("/etc/passwd")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsParentTraversalInSubpath() {
        assertThatThrownBy(() -> sandbox().fs().readText("sub/../../escape")).isInstanceOf(SandboxException.class);
    }
}
