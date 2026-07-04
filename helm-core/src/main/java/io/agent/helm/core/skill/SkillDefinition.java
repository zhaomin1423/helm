package io.agent.helm.core.skill;

import java.util.List;
import java.util.Objects;

/**
 * A reusable skill: a named bundle of instructions and resources that can be loaded from classpath or configured
 * directories and attached to an agent run. Skills are metadata-only from the perspective of {@code helm-core}; loading
 * is performed by runtime adapters.
 */
public record SkillDefinition(String name, String description, String instructions, List<SkillResource> resources) {
    public SkillDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        description = description == null ? "" : description;
        instructions = instructions == null ? "" : instructions;
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
    }
}
