package io.agent.helm.observability.logging;

import io.agent.helm.core.event.RuntimeEventObserver;
import io.agent.helm.core.event.RuntimeEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link RuntimeEventObserver} that emits structured, parameterized log lines for each runtime event. Lifecycle
 * events log at INFO, model/tool/provider activity at DEBUG, and errors at WARN. The observer relies on the runtime's
 * prior redaction and never re-serializes raw payloads at a higher level than the configured logger.
 */
public final class LoggingRuntimeObserver implements RuntimeEventObserver {

    private final Logger logger;

    public LoggingRuntimeObserver() {
        this(LoggerFactory.getLogger(LoggingRuntimeObserver.class));
    }

    public LoggingRuntimeObserver(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEvent(RuntimeEventRecord event) {
        String type = event.type();
        if (type == null || type.isBlank()) {
            return;
        }
        if (type.startsWith("error.") || type.endsWith(".failed")) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "helm event {} operation={} workflow={} sequence={}",
                        type,
                        event.operationId(),
                        event.workflowRunId(),
                        event.sequence());
            }
        } else if (type.contains("model.") || type.contains("tool.") || type.contains("skill.")) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "helm event {} operation={} workflow={} sequence={}",
                        type,
                        event.operationId(),
                        event.workflowRunId(),
                        event.sequence());
            }
        } else {
            if (logger.isInfoEnabled()) {
                logger.info(
                        "helm event {} operation={} workflow={} sequence={}",
                        type,
                        event.operationId(),
                        event.workflowRunId(),
                        event.sequence());
            }
        }
    }
}
