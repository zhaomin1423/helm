package io.agent.helm.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class ModelRefTest {
    @Test
    void parsesProviderAndModel() {
        ModelRef ref = ModelRef.parse("openai/gpt-4.1");

        assertThat(ref.providerId()).isEqualTo("openai");
        assertThat(ref.modelId()).isEqualTo("gpt-4.1");
        assertThat(ref.value()).isEqualTo("openai/gpt-4.1");
    }

    @Test
    void rejectsAmbiguousModelStrings() {
        assertThatThrownBy(() -> ModelRef.parse("gpt-4.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    void validatesDirectConstructorArguments() {
        assertThatThrownBy(() -> new ModelRef(null, "gpt-4.1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerId");

        assertThatThrownBy(() -> new ModelRef("openai", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
    }
}
