package io.agent.helm.core.security;

import io.agent.helm.core.error.HelmException;
import java.util.Map;
import java.util.Set;

/**
 * Identity and attributes of the caller driving a runtime operation. Extracted from the transport (HTTP header, Servlet
 * principal) and passed to {@link HelmAuthorizer}. {@code agent instance id} is not authorization — applications must
 * supply a security context. Null maps/sets are normalized to empty; null-valued map entries are skipped on copy.
 */
public record HelmSecurityContext(
        String principal, Set<String> roles, Map<String, Object> attributes, Map<String, Object> metadata) {
    public HelmSecurityContext {
        principal = principal == null ? "anonymous" : principal;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attributes = HelmException.copySafe(attributes);
        metadata = HelmException.copySafe(metadata);
    }

    public static HelmSecurityContext anonymous() {
        return new HelmSecurityContext("anonymous", Set.of(), Map.of(), Map.of());
    }
}
