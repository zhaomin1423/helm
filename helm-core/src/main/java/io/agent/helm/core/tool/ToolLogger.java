package io.agent.helm.core.tool;

import java.util.Map;

/**
 * Minimal structured-logger handle exposed to tools. Implementations route {@code (message, fields)} pairs to the
 * runtime's observability layer (e.g. SLF4J, OpenTelemetry logs). The runtime supplies a non-null {@code ToolLogger} to
 * every {@link ToolContext}; tools must not assume any specific backend and must not throw on logging.
 *
 * <p>The handle is intentionally free of slf4j/JUL dependencies so {@code helm-core} stays transport-agnostic.
 */
public interface ToolLogger {
    /**
     * Emits a debug-level structured log entry.
     *
     * @param message the static message template; never {@code null}.
     * @param fields structured key/value pairs; {@code null} is treated as empty.
     */
    void debug(String message, Map<String, Object> fields);

    /**
     * Emits an info-level structured log entry.
     *
     * @param message the static message template; never {@code null}.
     * @param fields structured key/value pairs; {@code null} is treated as empty.
     */
    void info(String message, Map<String, Object> fields);

    /**
     * Emits an error-level structured log entry, attaching the cause's stack trace.
     *
     * @param message the static message template; never {@code null}.
     * @param cause the underlying failure; may be {@code null}.
     * @param fields structured key/value pairs; {@code null} is treated as empty.
     */
    void error(String message, Throwable cause, Map<String, Object> fields);

    /** Returns a no-op logger that discards every entry; suitable for tests and offline tools. */
    static ToolLogger noop() {
        return NoopToolLogger.INSTANCE;
    }
}

/** Default no-op implementation. */
final class NoopToolLogger implements ToolLogger {
    static final NoopToolLogger INSTANCE = new NoopToolLogger();

    @Override
    public void debug(String message, Map<String, Object> fields) {}

    @Override
    public void info(String message, Map<String, Object> fields) {}

    @Override
    public void error(String message, Throwable cause, Map<String, Object> fields) {}
}
