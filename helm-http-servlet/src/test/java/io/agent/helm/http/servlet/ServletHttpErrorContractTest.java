package io.agent.helm.http.servlet;

import io.agent.helm.http.core.HelmHttpRequest;
import io.agent.helm.http.core.HelmHttpResponse;
import io.agent.helm.http.core.HttpErrorContractTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs {@link HttpErrorContractTest} over real HTTP via an embedded Jetty serving {@link HelmHttpServlet}. Verifies the
 * servlet adapter translates correctly and the standard routes work end-to-end over the wire.
 */
final class ServletHttpErrorContractTest extends HttpErrorContractTest {
    private Server server;
    private URI baseUri;
    private java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/", ServletContextHandler.NO_SESSIONS);
        context.addServlet(new ServletHolder(new HelmHttpServlet(router)), "/*");
        server.setHandler(context);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + connector.getLocalPort());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Override
    protected HelmHttpResponse send(HelmHttpRequest request) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(baseUri.resolve(request.path()))
                .method(
                        request.method(),
                        request.body().isEmpty()
                                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                : java.net.http.HttpRequest.BodyPublishers.ofString(
                                        request.body(), StandardCharsets.UTF_8));
        request.headers().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });
        java.net.http.HttpResponse<String> response = httpClient.send(
                builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach(headers::put);
        return new HelmHttpResponse(response.statusCode(), headers, response.body());
    }
}
