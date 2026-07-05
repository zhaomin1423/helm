package io.agent.helm.core.store;

import java.util.List;
import java.util.Optional;

/** Session lifecycle persistence. Sub-interface of {@link RuntimeStore}; adapters may implement this alone. */
public interface SessionStore {
    Optional<AgentSessionState> loadSession(String sessionId);

    void saveSession(AgentSessionState session);

    /** Lists all sessions, ordered by {@code createdAt} ascending. */
    List<AgentSessionState> listSessions();

    /** Deletes the session with the given id. Deleting an unknown id is a no-op. */
    void deleteSession(String sessionId);
}
