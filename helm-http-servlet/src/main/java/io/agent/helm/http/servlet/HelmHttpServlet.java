package io.agent.helm.http.servlet;

import io.agent.helm.http.core.HelmHttpRequest;
import io.agent.helm.http.core.HelmHttpResponse;
import io.agent.helm.http.core.HelmHttpRouter;
import io.agent.helm.http.core.HttpErrors;
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
 *
 * <p>Safe defaults: request bodies are capped at {@link #DEFAULT_MAX_BODY_BYTES} (1 MiB) by default and rejected with
 * HTTP 413 when exceeded; all responses are written as UTF-8. The servlet path is taken from
 * {@code HttpServletRequest.getPathInfo()} so a non-empty {@code routePrefix} (configured at registration time) is
 * stripped before the router sees the path.
 */
public final class HelmHttpServlet extends HttpServlet {
    /** Default cap on request body size: 1 MiB. Oversized bodies are rejected with HTTP 413. */
    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    private final HelmHttpRouter router;
    private final int maxBodyBytes;

    public HelmHttpServlet(HelmHttpRouter router) {
        this(router, DEFAULT_MAX_BODY_BYTES);
    }

    public HelmHttpServlet(HelmHttpRouter router, int maxBodyBytes) {
        this.router = Objects.requireNonNull(router, "router");
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
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
        // Fast path: reject oversized Content-Length up front so we never read the body.
        long declared = contentLength(req);
        if (declared > maxBodyBytes) {
            write(resp, HttpErrors.errorResponse(413, "PAYLOAD_TOO_LARGE", "request body exceeds limit", Map.of()));
            return;
        }
        // Bounded read: read at most maxBodyBytes+1 bytes. If we get more than maxBodyBytes, the body is too large
        // even if Content-Length was absent or chunked transfer-encoding was used. This bounds memory so a single
        // oversized POST cannot OOM the process.
        byte[] head = req.getInputStream().readNBytes(maxBodyBytes + 1);
        if (head.length > maxBodyBytes) {
            write(resp, HttpErrors.errorResponse(413, "PAYLOAD_TOO_LARGE", "request body exceeds limit", Map.of()));
            return;
        }
        String body = new String(head, StandardCharsets.UTF_8);
        // getPathInfo() returns the path below the servlet mapping (prefix-stripped); fall back to "/" when the
        // request hit the servlet path with no trailing path so the router always sees a non-null, prefix-free path.
        String path = req.getPathInfo();
        if (path == null) {
            path = "/";
        }
        HelmHttpRequest request = new HelmHttpRequest(req.getMethod(), path, Map.of(), headers, body);
        HelmHttpResponse response = router.handle(request);
        write(resp, response);
    }

    private static long contentLength(HttpServletRequest req) {
        try {
            return req.getContentLengthLong();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void write(HttpServletResponse resp, HelmHttpResponse response) throws IOException {
        resp.setStatus(response.status());
        // Always set Content-Type with explicit UTF-8 charset so non-ASCII JSON (CJK, emoji) is not corrupted by a
        // container default that may be ISO-8859-1. If the response already carries a Content-Type (e.g.
        // "text/event-stream"), override the charset only when the header is a JSON content type without a charset.
        boolean contentTypeSet = false;
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            String name = entry.getKey();
            for (String value : entry.getValue()) {
                if ("Content-Type".equalsIgnoreCase(name)) {
                    contentTypeSet = true;
                    value = withCharsetIfNeeded(value);
                }
                resp.addHeader(name, value);
            }
        }
        if (!contentTypeSet) {
            resp.setHeader("Content-Type", "application/json; charset=UTF-8");
        }
        // Force the response character encoding to UTF-8 for the writer regardless of the Content-Type header so the
        // servlet container does not transcode the body.
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(response.body());
        resp.getWriter().flush();
    }

    private static String withCharsetIfNeeded(String contentType) {
        if (contentType == null) {
            return contentType;
        }
        String lower = contentType.toLowerCase();
        if (lower.contains("charset=")) {
            return contentType;
        }
        return contentType + "; charset=UTF-8";
    }
}
