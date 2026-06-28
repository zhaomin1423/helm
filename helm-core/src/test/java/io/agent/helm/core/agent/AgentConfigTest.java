package io.agent.helm.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.model.ModelRef;
import org.junit.jupiter.api.Test;

final class AgentConfigTest {
    @Test
    void buildsAgentConfigWithModelAndInstructions() {
        AgentConfig config = AgentConfig.builder()
                .model("openai/gpt-4.1")
                .instructions("You are helpful.")
                .build();

        assertThat(config.model()).isEqualTo(ModelRef.parse("openai/gpt-4.1"));
        assertThat(config.instructions()).isEqualTo("You are helpful.");
        assertThat(config.tools()).isEmpty();
    }
}
