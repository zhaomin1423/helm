package io.agent.helm.core.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RateLimitKeyTest {
    @Test
    void principalAndAgentAndGlobalKeysHaveExplicitDimensions() {
        assertThat(RateLimitKey.principal("alice").dimension()).isEqualTo(RateLimitKey.PRINCIPAL);
        assertThat(RateLimitKey.agent("coding").dimension()).isEqualTo(RateLimitKey.AGENT);
        assertThat(RateLimitKey.global().dimension()).isEqualTo(RateLimitKey.GLOBAL);
    }

    @Test
    void rejectsNullDimension() {
        assertThatThrownBy(() -> new RateLimitKey(null, "v"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void rejectsBlankDimension() {
        assertThatThrownBy(() -> new RateLimitKey("  ", "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void nullValueIsNormalizedToEmpty() {
        RateLimitKey key = new RateLimitKey(RateLimitKey.GLOBAL, null);

        assertThat(key.value()).isEmpty();
    }
}
