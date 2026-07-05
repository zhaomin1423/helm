package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies that every HelmException code is registered in {@link ErrorCode} and codes are unique. */
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
    void helmExceptionRejectsUnknownCode() {
        assertThatThrownBy(() -> new TestHelmException("UNKNOWN_CODE", "m", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown HelmException code");
    }

    @Test
    void helmExceptionAcceptsRegisteredCode() {
        assertThat(new TestHelmException("AGENT_NOT_FOUND", "m", Map.of(), Map.of()).code())
                .isEqualTo("AGENT_NOT_FOUND");
    }

    @Test
    void errorCodeConstructorProducesStableString() {
        assertThat(new TestHelmException(ErrorCode.RATE_LIMITED, "m", Map.of(), Map.of()).code())
                .isEqualTo("RATE_LIMITED");
    }

    private static final class TestHelmException extends HelmException {
        TestHelmException(String code, String message, Map<String, Object> details, Map<String, Object> dev) {
            super(code, message, details, dev);
        }

        TestHelmException(ErrorCode code, String message, Map<String, Object> details, Map<String, Object> dev) {
            super(code, message, details, dev);
        }
    }
}
