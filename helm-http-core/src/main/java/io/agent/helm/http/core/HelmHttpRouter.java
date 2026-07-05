package io.agent.helm.http.core;

import io.agent.helm.core.error.AuthorizationException;
import io.agent.helm.core.security.AuthorizationResult;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.security.HelmAuthorizer;
import io.agent.helm.core.security.HelmResource;
import io.agent.helm.core.security.HelmSecurityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dispatches a {@link HelmHttpRequest} to the first registered route whose method and path pattern match, extracting
 * path parameters. When an {@link HelmAuthorizer} is configured, each matched route is authorized against the
 * {@link SecurityContextExtractor}-derived context before the handler runs. Unmatched requests return 404; handler
 * exceptions are mapped to the unified error response via {@link HttpErrors}.
 */
public final class HelmHttpRouter {
    private final List<Compiled> routes;
    private final HelmAuthorizer authorizer;
    private final SecurityContextExtractor extractor;

    private HelmHttpRouter(List<HttpRoute> routes, HelmAuthorizer authorizer, SecurityContextExtractor extractor) {
        this.routes = routes.stream()
                .map(r -> new Compiled(r, new PathPattern(r.pattern())))
                .toList();
        this.authorizer = authorizer;
        this.extractor = extractor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public HelmHttpResponse handle(HelmHttpRequest request) {
        for (Compiled compiled : routes) {
            if (!compiled.route.method().equalsIgnoreCase(request.method())) {
                continue;
            }
            Optional<Map<String, String>> params = compiled.pattern.match(request.path());
            if (params.isEmpty()) {
                continue;
            }
            HelmHttpRequest routed = new HelmHttpRequest(
                    request.method(), request.path(), params.get(), request.headers(), request.body());
            // authorize() invokes the application-provided extractor/authorizer; wrap it in the same try/catch so a
            // throwing extractor produces the unified JSON envelope instead of escaping to the container.
            HelmHttpResponse denied;
            try {
                denied = authorize(routed);
            } catch (Exception e) {
                return HttpErrors.toResponse(e);
            }
            if (denied != null) {
                return denied;
            }
            try {
                return compiled.route.handler().handle(routed);
            } catch (Exception e) {
                return HttpErrors.toResponse(e);
            }
        }
        return HttpErrors.notFound(request.path());
    }

    private HelmHttpResponse authorize(HelmHttpRequest request) {
        if (authorizer == null) {
            return null;
        }
        HelmSecurityContext ctx = extractor == null ? HelmSecurityContext.anonymous() : extractor.extract(request);
        HelmAction action = actionFor(request.method());
        AuthorizationResult result = authorizer.authorize(ctx, action, HelmResource.of("HTTP", request.path()));
        if (!result.allowed()) {
            AuthorizationException e = AuthorizationException.forbidden(
                    "Access denied: " + action,
                    Map.of("path", request.path(), "action", action.name(), "reason", result.reason()));
            return HttpErrors.toResponse(e);
        }
        return null;
    }

    private static HelmAction actionFor(String method) {
        return switch (method.toUpperCase()) {
            case "GET" -> HelmAction.READ_OPERATION;
            case "DELETE" -> HelmAction.RESET_SESSION;
            default -> HelmAction.PROMPT;
        };
    }

    private record Compiled(HttpRoute route, PathPattern pattern) {}

    public static final class Builder {
        private final List<HttpRoute> routes = new ArrayList<>();
        private HelmAuthorizer authorizer;
        private SecurityContextExtractor extractor;

        public Builder route(String method, String pattern, HelmHttpHandler handler) {
            routes.add(new HttpRoute(method, pattern, handler));
            return this;
        }

        public Builder route(HttpRoute route) {
            routes.add(route);
            return this;
        }

        /** Configures per-route authorization; routes are denied before the handler runs. */
        public Builder authorizer(HelmAuthorizer authorizer) {
            this.authorizer = authorizer;
            return this;
        }

        /** Extracts the security context from each request; required when an authorizer is configured. */
        public Builder securityContextExtractor(SecurityContextExtractor extractor) {
            this.extractor = extractor;
            return this;
        }

        public HelmHttpRouter build() {
            if (authorizer != null && extractor == null) {
                throw new IllegalStateException(
                        "securityContextExtractor is required when an authorizer is configured; otherwise the authorizer"
                                + " would always see an anonymous context");
            }
            if (authorizer == null && extractor != null) {
                throw new IllegalStateException(
                        "authorizer is required when a securityContextExtractor is configured; an extractor without an"
                                + " authorizer has no effect and indicates a misconfiguration");
            }
            return new HelmHttpRouter(List.copyOf(routes), authorizer, extractor);
        }
    }
}
