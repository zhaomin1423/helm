package io.agent.helm.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.runtime.OperationHandle;
import io.agent.helm.runtime.WorkflowRunHandle;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Default {@link HelmClient} implementation. Wires {@link HttpTransport} to {@link ClientErrorMapper} and
 * {@link SseParser}.
 */
final class DefaultHelmClient implements HelmClient {

    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final ClientErrorMapper errors;
    private final SseParser sse;

    DefaultHelmClient(HttpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
        this.errors = new ClientErrorMapper(mapper);
        this.sse = new SseParser(mapper);
    }

    @Override
    public PromptResult prompt(String agent, String instance, String session, String text) {
        String path = "/agents/" + enc(agent) + "/instances/" + enc(instance) + "/sessions/" + enc(session) + "/prompt";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        RawResponse response = transport.send("POST", path, body);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            String operationId = node.path("operationId").asText();
            String resultText = node.path("text").asText("");
            return new PromptResult(operationId, resultText);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse prompt response: " + e.getMessage(), e);
        }
    }

    @Override
    public OperationHandle dispatch(String agent, AgentDispatchRequest request) {
        String path = "/agents/" + enc(agent) + "/dispatch";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instance", request.instance() == null ? "" : request.instance());
        body.put("session", request.session() == null ? "" : request.session());
        body.put("text", request.text() == null ? "" : request.text());
        RawResponse response = transport.send("POST", path, body);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            String operationId = node.path("operationId").asText();
            String statusName = node.path("status").asText("SUCCEEDED");
            return new OperationHandle(operationId, io.agent.helm.core.store.OperationStatus.valueOf(statusName));
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse dispatch response: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<OperationRecord> getOperation(String operationId) {
        String path = "/operations/" + enc(operationId);
        RawResponse response = transport.send("GET", path, null);
        if (errors.isNotFound(response)) {
            return Optional.empty();
        }
        ensureSuccess(response);
        return Optional.of(parse(response.body(), OperationRecord.class));
    }

    @Override
    public List<RuntimeEventRecord> getOperationEvents(String operationId) {
        String path = "/operations/" + enc(operationId) + "/events";
        RawResponse response = transport.send("GET", path, null);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            JsonNode events = node.path("events");
            if (events.isMissingNode()) {
                return List.of();
            }
            return mapper.convertValue(events, new TypeReference<List<RuntimeEventRecord>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse operation events: " + e.getMessage(), e);
        }
    }

    @Override
    public List<OperationRecord> sessionOperations(String sessionId) {
        String path = "/sessions/" + enc(sessionId) + "/operations";
        RawResponse response = transport.send("GET", path, null);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            JsonNode ops = node.path("operations");
            if (ops.isMissingNode()) {
                return List.of();
            }
            return mapper.convertValue(ops, new TypeReference<List<OperationRecord>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse session operations: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AgentSessionState> listSessions() {
        String path = "/sessions";
        RawResponse response = transport.send("GET", path, null);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            JsonNode sessions = node.path("sessions");
            if (sessions.isMissingNode()) {
                return List.of();
            }
            return mapper.convertValue(sessions, new TypeReference<List<AgentSessionState>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse sessions: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AgentSessionState> getSession(String sessionId) {
        String path = "/sessions/" + enc(sessionId);
        RawResponse response = transport.send("GET", path, null);
        if (errors.isNotFound(response)) {
            return Optional.empty();
        }
        ensureSuccess(response);
        return Optional.of(parse(response.body(), AgentSessionState.class));
    }

    @Override
    public void resetSession(String sessionId) {
        String path = "/sessions/" + enc(sessionId);
        RawResponse response = transport.send("DELETE", path, null);
        if (response.status() == 204 || response.status() == 200) {
            return;
        }
        ensureSuccess(response);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <I, O> WorkflowRunHandle<O> invokeWorkflow(String workflow, I input) {
        String path = "/workflows/" + enc(workflow) + "/invoke";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input);
        RawResponse response = transport.send("POST", path, body);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            String runId = node.path("runId").asText();
            JsonNode resultNode = node.path("result");
            O result = (O) mapper.treeToValue(resultNode, Object.class);
            return new WorkflowRunHandle<>(runId, result);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse workflow invoke response: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<WorkflowRunRecord> getRun(String runId) {
        String path = "/workflow-runs/" + enc(runId);
        RawResponse response = transport.send("GET", path, null);
        if (errors.isNotFound(response)) {
            return Optional.empty();
        }
        ensureSuccess(response);
        return Optional.of(parse(response.body(), WorkflowRunRecord.class));
    }

    @Override
    public List<WorkflowRunRecord> workflowRuns(String workflow) {
        String path = "/workflows/" + enc(workflow) + "/runs";
        RawResponse response = transport.send("GET", path, null);
        ensureSuccess(response);
        try {
            JsonNode node = mapper.readTree(response.body());
            JsonNode runs = node.path("runs");
            if (runs.isMissingNode()) {
                return List.of();
            }
            return mapper.convertValue(runs, new TypeReference<List<WorkflowRunRecord>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse workflow runs: " + e.getMessage(), e);
        }
    }

    @Override
    public Flow.Publisher<PromptStreamEvent> promptStream(String agent, String instance, String session, String text) {
        String path = "/agents/" + enc(agent) + "/instances/" + enc(instance) + "/sessions/" + enc(session)
                + "/prompt/stream";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        SubmissionPublisher<PromptStreamEvent> publisher = new SubmissionPublisher<>();
        RawResponse response = transport.sendStream("POST", path, body);
        if (response.status() >= 400) {
            publisher.closeExceptionally(errors.toException(response));
            return publisher;
        }
        List<PromptStreamEvent> events = sse.parse(response.body());
        // Drain synchronously into the publisher; subscribers receive events on the publisher's executor.
        for (PromptStreamEvent event : events) {
            publisher.submit(event);
        }
        publisher.close();
        return publisher;
    }

    private void ensureSuccess(RawResponse response) {
        if (response.status() >= 400) {
            throw errors.toException(response);
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to parse response body into " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static String enc(String segment) {
        return URLEncoder.encode(segment == null ? "" : segment, StandardCharsets.UTF_8);
    }
}
