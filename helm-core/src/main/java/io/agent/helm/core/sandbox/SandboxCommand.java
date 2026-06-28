package io.agent.helm.core.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SandboxCommand(List<String> argv, Duration timeout, Map<String, String> environment) {
    public SandboxCommand {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }
}
