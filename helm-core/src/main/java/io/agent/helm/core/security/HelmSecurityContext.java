package io.agent.helm.core.security;

import java.util.Map;
import java.util.Set;

/**
 * Identity and attributes of the caller driving a runtime operation. Extracted from the transport (HTTP header, Servlet
 * principal) and passed to {@link HelmAuthorizer}. {@code agent instance id} is not authorization — applications must
 * supply a security context.
 */
public record HelmSecurityContext(
        String principal, Set<String> roles, Map<String, Object> attributes, Map<String, Object> metadata) {
    public HelmSecurityContext {
        principal = principal == null ? "anonymous" : principal;
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static HelmSecurityContext anonymous() {
        return new HelmSecurityContext("anonymous", Set.of(), Map.of(), Map.of());
    }
}
