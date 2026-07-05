package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HelmExceptionTest {
    @Test
    void exposesStableCodeAndSeparatesSafeAndDeveloperDetails() {
        HelmException error = new ValidationException("Invalid input", Map.of("field", "name"), Map.of("rawValue", ""));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.details()).containsEntry("field", "name");
        assertThat(error.developerDetails()).containsEntry("rawValue", "");
    }

    @Test
    void causeChainingConstructorPreservesCauseAndCode() {
        Throwable cause = new java.io.IOException("upstream");
        ToolExecutionException ex = new ToolExecutionException("boom", Map.of("tool", "t"), Map.of(), cause);

        assertThat(ex.code()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.details()).containsEntry("tool", "t");
    }

    @Test
    void nullMapsAreNormalizedToEmpty() {
        ValidationException ex = new ValidationException("m", null, null);

        assertThat(ex.details()).isEmpty();
        assertThat(ex.developerDetails()).isEmpty();
    }

    @Test
    void nullValuesAreSkippedRatherThanBlowingUp() {
        Map<String, Object> details = new HashMap<>();
        details.put("ok", "value");
        details.put("skip", null);
        details.put(null, "also-skip");

        ValidationException ex = new ValidationException("m", details, Map.of());

        assertThat(ex.details()).containsOnlyKeys("ok");
        assertThat(ex.details()).containsEntry("ok", "value");
    }

    @Test
    void contextOverflowWithKindSkipsNullsAndStaysUnmodifiable() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hint", "too long");
        details.put("ignored", null);

        ContextOverflowException ex = ContextOverflowException.prompt("overflow", details);

        assertThat(ex.details()).containsEntry("kind", ContextOverflowException.PROMPT_OVERFLOW);
        assertThat(ex.details()).containsEntry("hint", "too long");
        assertThat(ex.details()).doesNotContainKey("ignored");
        assertThatThrownBy(() -> ex.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sessionConflictCarriesVersionDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("storedVersion", 5L);
        details.put("requestedVersion", 4L);

        SessionConflictException ex = new SessionConflictException("stale version", details, Map.of());

        assertThat(ex.code()).isEqualTo("SESSION_CONFLICT");
        assertThat(ex.details()).containsEntry("storedVersion", 5L);
        assertThat(ex.details()).containsEntry("requestedVersion", 4L);
    }
}
