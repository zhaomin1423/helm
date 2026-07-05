package io.agent.helm.core.store;

import io.agent.helm.core.error.SessionConflictException;
import java.util.List;
import java.util.Optional;

/**
 * Session lifecycle persistence. Sub-interface of {@link RuntimeStore}; adapters may implement this alone.
 *
 * <p>{@link #saveSession} performs an optimistic-concurrency-control (compare-and-swap) write: if a session already
 * exists for {@code state.id()} with a version different from {@code state.version()}, the save must throw
 * {@link SessionConflictException} carrying the stored and requested versions in {@code details}. New sessions (no
 * existing row) are inserted directly. Callers should reload, reconcile, and retry on conflict.
 */
public interface SessionStore {
    Optional<AgentSessionState> loadSession(String sessionId);

    /**
     * Persists {@code session}, applying optimistic concurrency control on existing sessions.
     *
     * @param session the desired state; its {@code version} must match the currently stored version.
     * @throws SessionConflictException if a session already exists for {@code session.id()} with a version different
     *     from {@code session.version()}.
     */
    void saveSession(AgentSessionState session);

    /**
     * Lists all sessions, ordered by {@code createdAt} ascending.
     *
     * @param limit the maximum number of sessions to return; adapters MUST honor a sane default cap (e.g. 1000) even
     *     when {@code limit} is huge.
     */
    List<AgentSessionState> listSessions(int limit);

    /**
     * Lists all sessions, ordered by {@code createdAt} ascending. Convenience overload; equivalent to
     * {@link #listSessions(int) listSessions(Integer.MAX_VALUE)}.
     */
    default List<AgentSessionState> listSessions() {
        return listSessions(Integer.MAX_VALUE);
    }

    /** Deletes the session with the given id. Deleting an unknown id is a no-op. */
    void deleteSession(String sessionId);
}
