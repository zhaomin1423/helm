package io.agent.helm.http.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.WorkflowRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable HTTP error contract: assertions about status codes and the {@code {"error":{code,...}}} body shape across
 * the standard Helm routes. Transport adapters extend this and implement {@link #send(HelmHttpRequest)}; the router
 * subclass tests directly, the servlet subclass goes over HTTP.
 */
public abstract class HttpErrorContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    protected FakeProvider provider;
    protected AgentRuntime agentRuntime;
    protected WorkflowRuntime workflowRuntime;
    protected HelmHttpRouter router;

    @BeforeEach
    void setUpRuntimes() {
        provider = new FakeProvider("fake");
        agentRuntime = AgentRuntime.builder()
                .agent(new EchoAgent())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();
        workflowRuntime = WorkflowRuntime.builder()
                .workflow(new UpperWorkflow())
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();
        router = HelmHttpRoutes.router(agentRuntime, workflowRuntime);
    }

    protected abstract HelmHttpResponse send(HelmHttpRequest request) throws Exception;

    protected void enqueueOk(String text) {
        provider.enqueue(new ModelStreamEvent.ContentDelta(text), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
    }

    protected void failProvider() {
        provider.failWith(new ProviderException("boom", Map.of("status", 500), Map.of()));
    }

    protected static HelmHttpRequest request(String method, String path, String body) {
        return new HelmHttpRequest(
                method, path, Map.of(), Map.of("Content-Type", List.of("application/json")), body == null ? "" : body);
    }

    protected static String errorCode(HelmHttpResponse response) throws Exception {
        JsonNode node = MAPPER.readTree(response.body());
        return node.path("error").path("code").asText();
    }

    @Test
    void unknownAgentReturns404AgentNotFound() throws Exception {
        HelmHttpResponse response =
                send(request("POST", "/agents/unknown/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("AGENT_NOT_FOUND");
    }

    @Test
    void missingTextFieldReturns400ValidationFailed() throws Exception {
        HelmHttpResponse response = send(request("POST", "/agents/echo/instances/i/sessions/s/prompt", "{}"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void providerErrorReturns502ProviderError() throws Exception {
        failProvider();
        HelmHttpResponse response =
                send(request("POST", "/agents/echo/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}"));

        assertThat(response.status()).isEqualTo(502);
        assertThat(errorCode(response)).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void promptSucceedsReturns200WithText() throws Exception {
        enqueueOk("hello");
        HelmHttpResponse response =
                send(request("POST", "/agents/echo/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("hello");
    }

    @Test
    void operationIsInspectableAfterPrompt() throws Exception {
        enqueueOk("hello");
        HelmHttpResponse promptResponse =
                send(request("POST", "/agents/echo/instances/i/sessions/s/prompt", "{\"text\":\"hi\"}"));
        String operationId =
                MAPPER.readTree(promptResponse.body()).path("operationId").asText();

        HelmHttpResponse inspection = send(request("GET", "/operations/" + operationId, ""));
        assertThat(inspection.status()).isEqualTo(200);
        assertThat(inspection.body()).contains(operationId);
    }

    @Test
    void unknownWorkflowReturns404WorkflowNotFound() throws Exception {
        HelmHttpResponse response = send(request("POST", "/workflows/unknown/invoke", "{\"input\":{\"text\":\"hi\"}}"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("WORKFLOW_NOT_FOUND");
    }

    @Test
    void workflowInvokeSucceedsReturns200() throws Exception {
        HelmHttpResponse response = send(request("POST", "/workflows/upper/invoke", "{\"input\":{\"text\":\"hi\"}}"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("HI");
    }

    @Test
    void unknownOperationReturns404() throws Exception {
        HelmHttpResponse response = send(request("GET", "/operations/op_unknown", ""));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void unknownRouteReturns404NotFound() throws Exception {
        HelmHttpResponse response = send(request("GET", "/no-such-route", ""));

        assertThat(response.status()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
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
