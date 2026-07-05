package io.agent.helm.core.security;

import io.agent.helm.core.annotation.Experimental;

/**
 * Application-supplied authorization check. Called at admission (before an operation/run record is created) so denied
 * requests produce no side effects. Implementations must not throw; return {@link AuthorizationResult#deny} instead.
 * {@code @Experimental} the SPI shape is being validated against adapter feedback.
 */
@Experimental
public interface HelmAuthorizer {
    AuthorizationResult authorize(HelmSecurityContext context, HelmAction action, HelmResource resource);

    /**
     * Intended for local/dev use; the runtime layer may log a warning when this authorizer is active in a production
     * profile.
     */
    static HelmAuthorizer allowAll() {
        return (context, action, resource) -> AuthorizationResult.allow();
    }
}
