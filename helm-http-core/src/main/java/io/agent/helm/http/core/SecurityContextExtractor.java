package io.agent.helm.http.core;

import io.agent.helm.core.security.HelmSecurityContext;
import java.util.Map;
import java.util.Set;

/**
 * Extracts a {@link HelmSecurityContext} from an {@link HelmHttpRequest}, typically from auth/principal headers.
 * Implemented by HTTP adapters (Servlet) or supplied as a lambda; the result feeds {@link HelmHttpRouter}'s authorizer.
 *
 * <p>Applications must supply their own {@code SecurityContextExtractor} bean (e.g. validating a signed JWT, mTLS cert,
 * or platform-injected identity). The {@link #header()} convenience is <strong>dev-only</strong>: it trusts a
 * client-set {@code X-Helm-Principal} header verbatim, so any client can impersonate any principal. Wire it only behind
 * an explicit opt-in property (e.g. {@code helm.http.dev-header-auth=true}) and never in production.
 */
@FunctionalInterface
public interface SecurityContextExtractor {

    HelmSecurityContext extract(HelmHttpRequest request);

    /**
     * <strong>DEV-ONLY.</strong> Reads {@code X-Helm-Principal} from headers and trusts it verbatim. Returns anonymous
     * when absent so the authorizer can decide whether anonymous access is permitted. Any client can impersonate any
     * principal by setting this header, so this MUST NOT be auto-wired in production — gate it behind an explicit
     * opt-in property and document it as development-only.
     */
    static SecurityContextExtractor header() {
        return request -> {
            String principal = request.header("X-Helm-Principal");
            if (principal == null || principal.isBlank()) {
                return HelmSecurityContext.anonymous();
            }
            return new HelmSecurityContext(principal, Set.of(), Map.of(), Map.of());
        };
    }
}
