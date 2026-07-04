package io.agent.helm.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SkillDefinitionTest {
    @Test
    void buildsWithDefaultsAndCopiesResources() {
        SkillDefinition skill = new SkillDefinition("writer", null, null, List.of(new SkillResource("a.md", "x")));

        assertThat(skill.description()).isEqualTo("");
        assertThat(skill.instructions()).isEqualTo("");
        assertThat(skill.resources()).hasSize(1);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new SkillDefinition(" ", "d", "i", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
