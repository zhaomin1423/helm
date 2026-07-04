package io.agent.helm.sandbox.local;

import io.agent.helm.core.error.SandboxException;
import io.agent.helm.core.sandbox.SandboxCommand;
import io.agent.helm.core.sandbox.SandboxCommandResult;
import io.agent.helm.core.sandbox.SandboxShell;
import java.util.Map;

/** A {@link SandboxShell} that rejects every command. Used when shell execution is disabled. */
final class DisabledShell implements SandboxShell {
    private final String reason;

    DisabledShell(String reason) {
        this.reason = reason;
    }

    @Override
    public SandboxCommandResult execute(SandboxCommand command) {
        throw new SandboxException(reason, Map.of("argv", command.argv()), Map.of());
    }
}
