package io.agent.helm.core.security;

/** Outcome of {@link HelmAuthorizer#authorize}. {@code reason} is safe to expose in error responses. */
public record AuthorizationResult(boolean allowed, String reason) {
    public static AuthorizationResult allow() {
        return new AuthorizationResult(true, "");
    }

    public static AuthorizationResult deny(String reason) {
        return new AuthorizationResult(false, reason == null ? "" : reason);
    }
}
