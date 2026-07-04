package io.agent.helm.sandbox.local;

import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxContractTest;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

final class LocalSandboxContractTest extends SandboxContractTest {
    @TempDir
    Path tempDir;

    @Override
    protected Sandbox createSandbox() {
        return LocalSandbox.builder().root(tempDir).build();
    }
}
