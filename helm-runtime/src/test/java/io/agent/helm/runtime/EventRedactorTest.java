package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.runtime.internal.EventRedactor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EventRedactorTest {
    @Test
    void redactsNestedSecretsCaseVariantsAndDeveloperDetailsRecursively() {
        Map<String, Object> redacted = EventRedactor.redact(Map.of(
                "apiKey", "secret",
                "Authorization", "Bearer token",
                "headers",
                        Map.of(
                                "Authorization", "Bearer nested",
                                "x-request-id", "req-1"),
                "env",
                        Map.of(
                                "OPENAI_API_KEY", "sk-test",
                                "safe", "value"),
                "environment", Map.of("PASSWORD", "pw"),
                "details",
                        Map.of(
                                "code",
                                "BAD",
                                "message",
                                "keep",
                                "nested",
                                Map.of("secret", "hide"),
                                "list",
                                List.of(
                                        Map.of("api_key", "nested-secret"),
                                        Map.of("developerDetails", Map.of("token", "remove")))),
                "developerDetails",
                        Map.of(
                                "path",
                                "/local/path",
                                "list",
                                List.of(Map.of("developerDetails", Map.of("token", "remove"))))));

        assertThat(redacted).containsEntry("apiKey", "[REDACTED]");
        assertThat(redacted).containsEntry("Authorization", "[REDACTED]");
        assertThat(redacted.get("headers"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("Authorization", "[REDACTED]")
                .containsEntry("x-request-id", "req-1");
        assertThat(redacted.get("env"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("OPENAI_API_KEY", "[REDACTED]")
                .containsEntry("safe", "value");
        assertThat(redacted.get("environment"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("PASSWORD", "[REDACTED]");
        assertThat(redacted.get("details"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("code", "BAD")
                .containsEntry("message", "keep")
                .extractingByKey("nested")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("secret", "[REDACTED]");
        assertThat(redacted.get("details"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("list")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .element(0)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("api_key", "[REDACTED]");
        assertThat(redacted.get("details"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("list")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .element(1)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("developerDetails");
        assertThat(redacted).doesNotContainKey("developerDetails");
    }
}
