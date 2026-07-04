package io.agent.helm.examples.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** End-to-end test for the example Spring Boot application. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class ApplicationTest {

    @LocalServerPort
    int port;

    @Test
    void promptReturnsResultOverHttp() throws Exception {
        HttpResponse<String> response = send("POST", "/agents/echo/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
    }

    @Test
    void workflowInvokeReturnsResultOverHttp() throws Exception {
        HttpResponse<String> response = send("POST", "/workflows/upper/invoke", "{\"input\":{\"text\":\"hi\"}}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("HI");
    }

    @Test
    void unknownAgentReturns404() throws Exception {
        HttpResponse<String> response =
                send("POST", "/agents/unknown/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("AGENT_NOT_FOUND");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + path))
                                .method(
                                        method,
                                        body.isEmpty()
                                                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                                : java.net.http.HttpRequest.BodyPublishers.ofString(
                                                        body, StandardCharsets.UTF_8))
                                .header("Content-Type", "application/json")
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
