package io.agent.helm.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

/** Smoke-tests {@code helm dev}: starts the embedded server and hits a route over HTTP. */
final class HelmCliDevTest {
    @Test
    void devServerServesRoutes() throws Exception {
        DevCommand command = new DevCommand();
        command.appClass = TestHelmApp.class.getName();
        Server server = command.startServer(0);
        try {
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(
                            java.net.http.HttpRequest.newBuilder()
                                    .uri(URI.create("http://127.0.0.1:" + port + "/operations/op_unknown"))
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("NOT_FOUND");
        } finally {
            server.stop();
        }
    }
}
