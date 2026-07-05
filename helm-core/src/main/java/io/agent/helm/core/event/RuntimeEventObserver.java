package io.agent.helm.core.event;

/**
 * Observer of runtime events. Implementations receive every {@link RuntimeEventRecord} the runtime persists; events are
 * already redacted before reaching observers, so observers must not introduce new sensitive content. Used by
 * observability adapters such as the logging observer.
 *
 * <p><b>Contract:</b> implementations MUST be non-throwing and non-blocking. The runtime catches and logs any exception
 * thrown from {@link #onEvent} but observers must not rely on side-effect ordering, must not rethrow, and must return
 * promptly (offloading slow work to a separate queue/thread if needed). Throwing or blocking degrades runtime
 * throughput and is treated as a bug in the observer, not a recoverable failure.
 */
@FunctionalInterface
public interface RuntimeEventObserver {
    void onEvent(RuntimeEventRecord event);
}
