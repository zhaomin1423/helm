package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every HelmException code is registered in {@link ErrorCode}, codes are unique, and
 * {@link ErrorCode#stable()} pins to {@link Enum#name()} for the current enum set.
 */
class ErrorCodeContractTest {

    @Test
    void allRegisteredCodesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(seen.add(code.stable()))
                    .as("duplicate ErrorCode: %s", code)
                    .isTrue();
        }
    }

    @Test
    void stableEqualsNameForAllCurrentValues() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.stable())
                    .as("stable() must equal name() for %s", code)
                    .isEqualTo(code.name());
        }
    }

    @Test
    void helmExceptionAcceptsRegisteredCode() {
        assertThat(new TestHelmException(ErrorCode.AGENT_NOT_FOUND, "m", Map.of(), Map.of()).code())
                .isEqualTo("AGENT_NOT_FOUND");
    }

    @Test
    void helmExceptionChainsCauseThroughSuperConstructor() {
        Throwable cause = new IllegalStateException("upstream");
        TestHelmException ex = new TestHelmException(ErrorCode.RATE_LIMITED, "m", Map.of(), Map.of(), cause);

        assertThat(ex.code()).isEqualTo("RATE_LIMITED");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    private static final class TestHelmException extends HelmException {
        TestHelmException(ErrorCode code, String message, Map<String, Object> details, Map<String, Object> dev) {
            super(code, message, details, dev);
        }

        TestHelmException(
                ErrorCode code, String message, Map<String, Object> details, Map<String, Object> dev, Throwable cause) {
            super(code, message, details, dev, cause);
        }
    }
}
