package io.agent.helm.http.servlet;

import io.agent.helm.http.core.HelmHttpRequest;
import io.agent.helm.http.core.HelmHttpResponse;
import io.agent.helm.http.core.HelmHttpRouter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts the framework-neutral {@link HelmHttpRouter} onto the Jakarta Servlet API. The only Servlet-aware module: it
 * translates a {@link HttpServletRequest} into a {@link HelmHttpRequest}, dispatches via the router, and writes the
 * {@link HelmHttpResponse} back.
 */
public final class HelmHttpServlet extends HttpServlet {
    private final HelmHttpRouter router;

    public HelmHttpServlet(HelmHttpRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new java.util.ArrayList<>();
            Enumeration<String> valueEnum = req.getHeaders(name);
            while (valueEnum.hasMoreElements()) {
                values.add(valueEnum.nextElement());
            }
            headers.put(name, values);
        }
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        HelmHttpRequest request = new HelmHttpRequest(req.getMethod(), req.getRequestURI(), Map.of(), headers, body);
        HelmHttpResponse response = router.handle(request);
        write(resp, response);
    }

    private static void write(HttpServletResponse resp, HelmHttpResponse response) throws IOException {
        resp.setStatus(response.status());
        response.headers().forEach((name, values) -> {
            for (String value : values) {
                resp.addHeader(name, value);
            }
        });
        resp.getWriter().write(response.body());
        resp.getWriter().flush();
    }
}
