package io.agent.helm.core.skill;

import java.util.List;
import java.util.Optional;

/**
 * Loads {@link SkillDefinition} instances from a source such as the classpath or a configured directory.
 * Implementations must reject skill names that would escape the loader root.
 */
public interface SkillLoader {
    List<SkillDefinition> loadAll();

    Optional<SkillDefinition> load(String name);
}
