package io.agent.helm.engine;

/**
 * Receives {@link EngineEvent}s emitted during {@link AgentEngine#run}. Implementations must not throw; the engine
 * treats listener failures as best-effort and continues the loop.
 */
@FunctionalInterface
public interface EngineEventListener {
    void onEvent(EngineEvent event);

    static EngineEventListener noop() {
        return event -> {};
    }
}
