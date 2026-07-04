package io.agent.helm.runtime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.error.ValidationException;
import io.agent.helm.core.skill.SkillDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClasspathSkillLoaderTest {
    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    @Test
    void loadsValidSkillWithResources() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skills");

        SkillDefinition writer = loader.load("writer").orElseThrow();

        assertThat(writer.name()).isEqualTo("writer");
        assertThat(writer.description()).isEqualTo("A writing assistant skill");
        assertThat(writer.instructions()).contains("write clearly");
        assertThat(writer.resources()).hasSize(1);
        assertThat(writer.resources().get(0).relativePath()).isEqualTo("template.md");
        assertThat(writer.resources().get(0).content()).contains("Draft Template");
    }

    @Test
    void loadMissingReturnsEmpty() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skills");

        assertThat(loader.load("nonexistent")).isEmpty();
    }

    @Test
    void loadInvalidSkillThrows() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skills");

        assertThatThrownBy(() -> loader.load("broken"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("description");
    }

    @Test
    void loadAllReturnsValidSkills() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skill-pack");

        List<SkillDefinition> skills = loader.loadAll();

        assertThat(skills).extracting(SkillDefinition::name).containsExactly("alpha");
    }

    @Test
    void loadAllFailsFastOnInvalidSkill() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skills");

        assertThatThrownBy(() -> loader.loadAll()).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsInvalidSkillName() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader(classLoader, "helm/skills");

        assertThatThrownBy(() -> loader.load("..")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> loader.load("a/b")).isInstanceOf(ValidationException.class);
    }
}
