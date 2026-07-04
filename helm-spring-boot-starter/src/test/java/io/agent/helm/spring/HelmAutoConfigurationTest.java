package io.agent.helm.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** Verifies the Spring Boot starter auto-configures runtimes and mounts HTTP routes. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HelmAutoConfigurationTest.TestApp.class,
        properties = "helm.http.enabled=true")
final class HelmAutoConfigurationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @LocalServerPort
    int port;

    @Test
    void unknownAgentReturns404AgentNotFound() throws Exception {
        HttpResponse<String> response =
                send("POST", "/agents/unknown/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("AGENT_NOT_FOUND");
    }

    @Test
    void missingTextFieldReturns400ValidationFailed() throws Exception {
        HttpResponse<String> response = send("POST", "/agents/echo/instances/i/sessions/s/prompt", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void promptSucceedsReturns200() throws Exception {
        HttpResponse<String> response = send("POST", "/agents/echo/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
    }

    @Test
    void workflowInvokeSucceedsReturns200() throws Exception {
        HttpResponse<String> response = send("POST", "/workflows/upper/invoke", "{\"input\":{\"text\":\"hi\"}}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("HI");
    }

    @Test
    void unknownWorkflowReturns404() throws Exception {
        HttpResponse<String> response = send("POST", "/workflows/unknown/invoke", "{\"input\":{}}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("WORKFLOW_NOT_FOUND");
    }

    @Test
    void unknownOperationReturns404() throws Exception {
        HttpResponse<String> response = send("GET", "/operations/op_unknown", "");

        assertThat(response.statusCode()).isEqualTo(404);
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

    private static String errorCode(HttpResponse<String> response) throws Exception {
        JsonNode node = MAPPER.readTree(response.body());
        return node.path("error").path("code").asText();
    }

    @SpringBootApplication
    static class TestApp {
        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @Bean
        AgentDefinition echoAgent() {
            return new EchoAgent();
        }

        @Bean
        WorkflowDefinition<?, ?> upperWorkflow() {
            return new UpperWorkflow();
        }

        @Bean
        ModelProvider provider() {
            return new ConstantFakeProvider("fake");
        }
    }

    static final class EchoAgent implements AgentDefinition {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder()
                    .model("fake/test")
                    .instructions("Echo.")
                    .build();
        }
    }

    static final class UpperWorkflow implements WorkflowDefinition<Object, String> {
        @Override
        public String name() {
            return "upper";
        }

        @Override
        public WorkflowConfig config() {
            return WorkflowConfig.of(new EchoAgent());
        }

        @Override
        public TypeDescriptor<Object> inputType() {
            return new TypeDescriptor<>() {};
        }

        @Override
        public TypeDescriptor<String> outputType() {
            return TypeDescriptor.of(String.class);
        }

        @Override
        public String run(WorkflowContext<Object> context) {
            Object input = context.input();
            if (input instanceof JsonNode node) {
                return node.path("text").asText("").toUpperCase();
            }
            return String.valueOf(input).toUpperCase();
        }
    }
}
