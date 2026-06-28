package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;

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
}
