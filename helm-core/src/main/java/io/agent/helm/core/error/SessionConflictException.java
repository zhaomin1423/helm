package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised by {@code SessionStore.saveSession} when an optimistic-concurrency-control check fails: a session exists with
 * a version that does not match the version the caller expected. Callers should reload the session, reconcile, and
 * retry.
 */
public final class SessionConflictException extends HelmException {
    public SessionConflictException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.SESSION_CONFLICT, message, details, developerDetails);
    }

    public SessionConflictException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.SESSION_CONFLICT, message, details, developerDetails, cause);
    }
}
