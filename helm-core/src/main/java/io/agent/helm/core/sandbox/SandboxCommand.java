package io.agent.helm.core.sandbox;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A command to execute in a sandbox shell. {@code argv} is the argument vector (argv[0] is the command),
 * {@code timeout} bounds execution, and {@code environment} is the variable overlay. Null maps are normalized to empty;
 * null-valued entries are skipped on copy.
 */
public record SandboxCommand(List<String> argv, Duration timeout, Map<String, String> environment) {
    public SandboxCommand {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        environment = copyEnv(environment);
    }

    /** Defensive copy helper for {@code Map<String, String>}: tolerates null source and null values. */
    private static Map<String, String> copyEnv(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
