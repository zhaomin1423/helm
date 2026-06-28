package io.github.zhaomin.helm.examples.codingworkflow;

import io.github.zhaomin.helm.AgentConfig;
import io.github.zhaomin.helm.AgentContext;
import io.github.zhaomin.helm.AgentDefinition;
import io.github.zhaomin.helm.Sandboxes;
import io.github.zhaomin.helm.SkillDefinition;

public final class CodingAgent implements AgentDefinition {
    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
            .model("anthropic/claude-sonnet")
            .instructions("""
                You are a careful software engineering agent.
                Work from the bound GitHub issue and repository context only.
                Produce a design before editing code.
                Treat failed verification and review findings as blockers.
                Never create a pull request directly; the workflow owns that step.
                """)
            .skill(SkillDefinition.fromClasspath("skills/design/SKILL.md"))
            .skill(SkillDefinition.fromClasspath("skills/design-review/SKILL.md"))
            .skill(SkillDefinition.fromClasspath("skills/implementation/SKILL.md"))
            .skill(SkillDefinition.fromClasspath("skills/code-review/SKILL.md"))
            .sandbox(Sandboxes.local())
            .build();
    }
}
