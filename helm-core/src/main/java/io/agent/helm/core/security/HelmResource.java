package io.agent.helm.core.security;

import io.agent.helm.core.error.HelmException;
import java.util.Map;

/**
 * The resource an action targets: a type (e.g. {@code AGENT}, {@code SESSION}), a name, and extra attributes. Null maps
 * are normalized to empty; null-valued entries are skipped on copy.
 */
public record HelmResource(String type, String name, Map<String, Object> attributes) {
    public HelmResource {
        type = type == null ? "" : type;
        name = name == null ? "" : name;
        attributes = HelmException.copySafe(attributes);
    }

    public static HelmResource of(String type, String name) {
        return new HelmResource(type, name, Map.of());
    }
}
