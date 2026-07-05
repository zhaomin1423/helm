package io.agent.helm.core.tool;

import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.security.HelmSecurityContext;
import java.time.Clock;
import java.util.Objects;

/**
 * Execution context handed to {@link Tool#execute}. The runtime populates every field before invoking a tool: the
 * operation id driving the call, the caller's security context, the sandbox (or {@code null} when sandboxing is not
 * enabled for the agent), a {@link Clock} for deterministic time, and a {@link ToolLogger} for structured logs.
 *
 * @param operationId the id of the operation that triggered this tool call; never {@code null}.
 * @param securityContext the caller's security context; never {@code null}.
 * @param sandbox the sandbox the tool may use, or {@code null} when sandboxing is disabled.
 * @param clock the runtime clock; never {@code null}.
 * @param logger the structured logger; never {@code null}.
 */
public record ToolContext(
        String operationId, HelmSecurityContext securityContext, Sandbox sandbox, Clock clock, ToolLogger logger) {
    public ToolContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(securityContext, "securityContext");
        // sandbox may be null when the agent does not enable sandboxing.
        clock = Objects.requireNonNullElse(clock, Clock.systemUTC());
        logger = Objects.requireNonNullElse(logger, ToolLogger.noop());
    }

    /**
     * Convenience factory for tests and offline tools: sandbox is {@code null}, clock is {@link Clock#systemUTC()}, and
     * logger is {@link ToolLogger#noop()}.
     */
    public static ToolContext of(String operationId, HelmSecurityContext securityContext) {
        return new ToolContext(operationId, securityContext, null, Clock.systemUTC(), ToolLogger.noop());
    }
}
