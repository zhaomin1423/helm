package io.agent.helm.core.skill;

import java.util.Objects;

/**
 * A single text resource attached to a {@link SkillDefinition}, addressed by a path relative to the skill root.
 * Resource paths must never escape the skill root; loaders reject traversal.
 */
public record SkillResource(String relativePath, String content) {
    public SkillResource {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("skill resource relativePath must not be blank");
        }
        Objects.requireNonNull(content, "content");
    }
}
