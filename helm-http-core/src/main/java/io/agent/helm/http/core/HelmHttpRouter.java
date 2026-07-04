package io.agent.helm.http.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dispatches a {@link HelmHttpRequest} to the first registered route whose method and path pattern match, extracting
 * path parameters. Unmatched requests return 404; handler exceptions are mapped to the unified error response via
 * {@link HttpErrors}.
 */
public final class HelmHttpRouter {
    private final List<Compiled> routes;

    private HelmHttpRouter(List<HttpRoute> routes) {
        this.routes = routes.stream()
                .map(r -> new Compiled(r, new PathPattern(r.pattern())))
                .toList();
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
            try {
                return compiled.route.handler().handle(routed);
            } catch (Exception e) {
                return HttpErrors.toResponse(e);
            }
        }
        return HttpErrors.notFound(request.path());
    }

    private record Compiled(HttpRoute route, PathPattern pattern) {}

    public static final class Builder {
        private final List<HttpRoute> routes = new ArrayList<>();

        public Builder route(String method, String pattern, HelmHttpHandler handler) {
            routes.add(new HttpRoute(method, pattern, handler));
            return this;
        }

        public Builder route(HttpRoute route) {
            routes.add(route);
            return this;
        }

        public HelmHttpRouter build() {
            return new HelmHttpRouter(List.copyOf(routes));
        }
    }
}
