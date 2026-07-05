package io.agent.helm.core.security;

import io.agent.helm.core.annotation.Experimental;

/**
 * Application-supplied authorization check. Called at admission (before an operation/run record is created) so denied
 * requests produce no side effects. Implementations must not throw; return {@link AuthorizationResult#deny}
 * instead. @Experimental the SPI shape is being validated against adapter feedback.
 */
@Experimental
public interface HelmAuthorizer {
    AuthorizationResult authorize(HelmSecurityContext context, HelmAction action, HelmResource resource);

    /** Default-allow authorizer for local/dev use; logs a warning in production. */
    static HelmAuthorizer allowAll() {
        return (context, action, resource) -> AuthorizationResult.allow();
    }
}
