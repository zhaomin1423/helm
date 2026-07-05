package io.agent.helm.core.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class AcquisitionResultTest {
    @Test
    void allowReportsUnknownRemaining() {
        AcquisitionResult result = AcquisitionResult.allow();

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterMs()).isZero();
        assertThat(result.remaining()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void allowWithRemainingReportsConcreteQuota() {
        AcquisitionResult result = AcquisitionResult.allow(42L);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(OptionalLong.of(42L));
    }

    @Test
    void deniedClampsRetryAfterAndReportsZeroRemaining() {
        AcquisitionResult result = AcquisitionResult.denied(-5L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterMs()).isZero();
        assertThat(result.remaining()).isEqualTo(OptionalLong.of(0L));
    }

    @Test
    void remainingIsNeverNegativeSentinel() {
        assertThat(AcquisitionResult.allow().remaining()).isEqualTo(OptionalLong.empty());
        assertThat(AcquisitionResult.allow(0L).remaining()).isEqualTo(OptionalLong.of(0L));
        assertThat(AcquisitionResult.denied(100L).remaining()).isEqualTo(OptionalLong.of(0L));
    }

    @Test
    void noLongSentinelMagicValueAllowedThroughRemaining() {
        // The old -1 sentinel is gone: remaining is OptionalLong, so -1 must be passed explicitly if ever intended.
        AcquisitionResult result = AcquisitionResult.allow(-1L);
        assertThat(result.remaining()).isEqualTo(OptionalLong.of(-1L));
        assertThatThrownBy(() -> OptionalLong.empty().getAsLong()).isInstanceOf(java.util.NoSuchElementException.class);
    }
}
