package io.agent.helm.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.security.HelmSecurityContext;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ToolContextTest {
    @Test
    void ofPopulatesDefaultsForSandboxClockAndLogger() {
        ToolContext context = ToolContext.of("op-1", HelmSecurityContext.anonymous());

        assertThat(context.operationId()).isEqualTo("op-1");
        assertThat(context.securityContext()).isEqualTo(HelmSecurityContext.anonymous());
        assertThat(context.sandbox()).isNull();
        assertThat(context.clock()).isEqualTo(Clock.systemUTC());
        assertThat(context.logger()).isSameAs(ToolLogger.noop());
    }

    @Test
    void canonicalConstructorRequiresOperationIdAndSecurityContext() {
        assertThatThrownBy(() -> new ToolContext(null, HelmSecurityContext.anonymous(), null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operationId");
        assertThatThrownBy(() -> new ToolContext("op", null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("securityContext");
    }

    @Test
    void nullClockAndLoggerAreReplacedWithDefaults() {
        ToolContext context = new ToolContext("op", HelmSecurityContext.anonymous(), null, null, null);

        assertThat(context.clock()).isEqualTo(Clock.systemUTC());
        assertThat(context.logger()).isSameAs(ToolLogger.noop());
    }

    @Test
    void noopLoggerDiscardsAllEntries() {
        ToolLogger logger = ToolLogger.noop();

        // Should not throw on any method.
        logger.debug("msg", Map.of("k", "v"));
        logger.info("msg", Map.of());
        logger.error("msg", new RuntimeException("boom"), Map.of());
    }
}
