package io.agent.helm.http.core;

import io.agent.helm.core.security.HelmSecurityContext;
import java.util.Map;
import java.util.Set;

/**
 * Extracts a {@link HelmSecurityContext} from an {@link HelmHttpRequest}, typically from auth/principal headers.
 * Implemented by HTTP adapters (Servlet) or supplied as a lambda; the result feeds {@link HelmHttpRouter}'s authorizer.
 */
@FunctionalInterface
public interface SecurityContextExtractor {

    HelmSecurityContext extract(HelmHttpRequest request);

    /**
     * Default extractor: reads {@code X-Helm-Principal} from headers. Returns anonymous when absent so the authorizer
     * can decide whether anonymous access is permitted.
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
