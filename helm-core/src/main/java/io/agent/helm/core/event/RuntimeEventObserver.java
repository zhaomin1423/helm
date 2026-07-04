package io.agent.helm.core.event;

/**
 * Observer of runtime events. Implementations receive every {@link RuntimeEventRecord} the runtime persists; events are
 * already redacted before reaching observers, so observers must not introduce new sensitive content. Used by
 * observability adapters such as the logging observer.
 */
public interface RuntimeEventObserver {
    void onEvent(RuntimeEventRecord event);
}
