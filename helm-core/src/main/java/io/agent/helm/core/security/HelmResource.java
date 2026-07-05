package io.agent.helm.core.security;

import java.util.Map;

/** The resource an action targets: a type (e.g. {@code AGENT}, {@code SESSION}), a name, and extra attributes. */
public record HelmResource(String type, String name, Map<String, Object> attributes) {
    public HelmResource {
        type = type == null ? "" : type;
        name = name == null ? "" : name;
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    public static HelmResource of(String type, String name) {
        return new HelmResource(type, name, Map.of());
    }
}
