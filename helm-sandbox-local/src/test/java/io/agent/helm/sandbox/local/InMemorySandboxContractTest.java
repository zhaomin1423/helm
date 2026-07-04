package io.agent.helm.sandbox.local;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxCommand;
import io.agent.helm.core.sandbox.SandboxContractTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InMemorySandboxContractTest extends SandboxContractTest {
    @Override
    protected Sandbox createSandbox() {
        return new InMemorySandbox();
    }

    @Test
    void shellIsUnsupported() {
        assertThatThrownBy(() -> sandbox()
                        .shell()
                        .execute(new SandboxCommand(List.of("echo", "hi"), Duration.ofSeconds(1), Map.of())))
                .isInstanceOf(SandboxException.class);
    }
}
