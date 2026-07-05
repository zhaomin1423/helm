package io.agent.helm.http.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.error.ValidationException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.runtime.AgentPromptRequest;
import io.agent.helm.runtime.AgentRuntime;
import io.agent.helm.runtime.OperationHandle;
import io.agent.helm.runtime.WorkflowInvokeRequest;
import io.agent.helm.runtime.WorkflowRunHandle;
import io.agent.helm.runtime.WorkflowRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds the standard Helm {@link HelmHttpRouter} from an {@link AgentRuntime} and {@link WorkflowRuntime}. */
public final class HelmHttpRoutes {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private HelmHttpRoutes() {}

    public static HelmHttpRouter router(AgentRuntime agentRuntime, WorkflowRuntime workflowRuntime) {
        return router(agentRuntime, workflowRuntime, null, null);
    }

    /**
     * Builds the standard router with per-route authorization; pass {@code null} authorizer to disable.
     *
     * <p><strong>Security context propagation (finding 8):</strong> the HTTP router authorizes each request against the
     * {@link HelmSecurityContext} produced by the {@link SecurityContextExtractor}, but the underlying
     * {@link AgentRuntime} entry points ({@code prompt}/{@code dispatch}) re-authorize against the runtime's
     * {@code defaultSecurityContext}. The runtime does not currently expose a {@code prompt(request, securityContext)}
     * entry point, so the HTTP-extracted context is not propagated into the runtime. When HTTP authorization is
     * enabled, leave the {@code AgentRuntime}'s authorizer unconfigured (it defaults to {@code allowAll}) so the HTTP
     * layer is the single authorization gate; the runtime's {@code defaultSecurityContext} still governs
     * runtime-internal authorization (e.g. tool execution) and should be set to a service-level principal when needed.
     * Double-authorization (HTTP authorizer + runtime authorizer) would authorize the HTTP call against the extracted
     * principal but the runtime call against {@code anonymous}, producing confusing deny/allow outcomes.
     */
    public static HelmHttpRouter router(
            AgentRuntime agentRuntime,
            WorkflowRuntime workflowRuntime,
            io.agent.helm.core.security.HelmAuthorizer authorizer,
            SecurityContextExtractor extractor) {
        Objects.requireNonNull(agentRuntime, "agentRuntime");
        Objects.requireNonNull(workflowRuntime, "workflowRuntime");
        return HelmHttpRouter.builder()
                .authorizer(authorizer)
                .securityContextExtractor(extractor)
                .route(
                        "POST",
                        "/agents/{agent}/instances/{instance}/sessions/{session}/prompt",
                        promptHandler(agentRuntime))
                .route(
                        "POST",
                        "/agents/{agent}/instances/{instance}/sessions/{session}/prompt/stream",
                        promptStreamHandler(agentRuntime))
                .route("POST", "/agents/{agent}/dispatch", dispatchHandler(agentRuntime))
                .route("POST", "/workflows/{workflow}/invoke", invokeHandler(workflowRuntime))
                .route("GET", "/operations/{id}", getOperationHandler(agentRuntime))
                .route("GET", "/operations/{id}/events", getOperationEventsHandler(agentRuntime))
                .route("GET", "/sessions/{id}/operations", sessionOperationsHandler(agentRuntime))
                .route("GET", "/sessions", listSessionsHandler(agentRuntime))
                .route("GET", "/sessions/{id}", getSessionHandler(agentRuntime))
                .route("DELETE", "/sessions/{id}", resetSessionHandler(agentRuntime))
                .route("GET", "/operations", listOperationsHandler(agentRuntime))
                .route("GET", "/workflow-runs/{id}", getRunHandler(workflowRuntime))
                .route("GET", "/workflows/{workflow}/runs", workflowRunsHandler(workflowRuntime))
                .build();
    }

    static HelmHttpHandler listSessionsHandler(AgentRuntime runtime) {
        return request -> HelmHttpResponse.ok(toJson(Map.of("sessions", runtime.listSessions())));
    }

    static HelmHttpHandler getSessionHandler(AgentRuntime runtime) {
        return request -> runtime.getSession(request.pathParam("id"))
                .<HelmHttpResponse>map(session -> HelmHttpResponse.ok(toJson(session)))
                .orElseGet(() -> HttpErrors.errorResponse(404, "SESSION_NOT_FOUND", "session not found", Map.of()));
    }

    static HelmHttpHandler resetSessionHandler(AgentRuntime runtime) {
        return request -> {
            runtime.resetSession(request.pathParam("id"));
            return HelmHttpResponse.ok(toJson(Map.of("reset", true)));
        };
    }

    static HelmHttpHandler listOperationsHandler(AgentRuntime runtime) {
        return request -> HelmHttpResponse.ok(toJson(Map.of("operations", runtime.listOperations())));
    }

    static HelmHttpHandler promptHandler(AgentRuntime runtime) {
        return request -> {
            String text = readField(request.body(), "text");
            PromptResult result = runtime.prompt(new AgentPromptRequest(
                    request.pathParam("agent"), request.pathParam("instance"), request.pathParam("session"), text));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationId", result.operationId());
            body.put("text", result.text());
            return HelmHttpResponse.ok(toJson(body));
        };
    }

    /** Streams prompt events as Server-Sent Events ({@code text/event-stream}). @Preview incremental surface. */
    static HelmHttpHandler promptStreamHandler(AgentRuntime runtime) {
        return request -> {
            String text = readField(request.body(), "text");
            java.util.concurrent.Flow.Publisher<PromptStreamEvent> pub = runtime.promptStream(new AgentPromptRequest(
                    request.pathParam("agent"), request.pathParam("instance"), request.pathParam("session"), text));
            StringBuilder sse = new StringBuilder();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription> subscriptionRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            pub.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(PromptStreamEvent event) {
                    try {
                        sse.append("data: ")
                                .append(MAPPER.writeValueAsString(event))
                                .append("\n\n");
                    } catch (Exception ignored) {
                        sse.append("data: {}\n\n");
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    done.countDown();
                }
            });
            boolean completed;
            try {
                completed = done.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HttpErrors.toResponse(new io.agent.helm.core.error.EngineInterruptedException(
                        "Interrupted while streaming prompt", Map.of(), Map.of()));
            } finally {
                // Always cancel the subscription on exit (success, timeout, or interruption) so a slow publisher
                // cannot keep producing into a dead subscriber.
                cancelSubscription(subscriptionRef);
            }
            // If the publisher failed (or we never completed within the timeout), surface a non-200 error response
            // instead of returning 200 with partial data.
            Throwable failureCause = failure.get();
            if (failureCause != null) {
                return HttpErrors.toResponse(failureCause);
            }
            if (!completed) {
                return HttpErrors.toResponse(new io.agent.helm.core.error.TurnTimeoutException(
                        "Prompt stream timed out after 30s", Map.of("timeoutMs", 30000L), Map.of()));
            }
            return new HelmHttpResponse(200, Map.of("Content-Type", List.of("text/event-stream")), sse.toString());
        };
    }

    private static void cancelSubscription(
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription> subscriptionRef) {
        java.util.concurrent.Flow.Subscription current = subscriptionRef.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }

    static HelmHttpHandler dispatchHandler(AgentRuntime runtime) {
        return request -> {
            JsonNode node = readObject(request.body());
            String instance = readField(node, "instance");
            String session = readField(node, "session");
            String text = readField(node, "text");
            OperationHandle handle =
                    runtime.dispatch(new AgentPromptRequest(request.pathParam("agent"), instance, session, text));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationId", handle.operationId());
            body.put("status", handle.status().name());
            return HelmHttpResponse.accepted(toJson(body));
        };
    }

    static HelmHttpHandler invokeHandler(WorkflowRuntime runtime) {
        return request -> {
            JsonNode node = readObject(request.body());
            Object input = node.path("input").isMissingNode() ? null : node.get("input");
            @SuppressWarnings({"rawtypes", "unchecked"})
            WorkflowRunHandle<?> handle =
                    runtime.invoke(new WorkflowInvokeRequest(request.pathParam("workflow"), input));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", handle.runId());
            body.put("result", handle.result());
            return HelmHttpResponse.ok(toJson(body));
        };
    }

    static HelmHttpHandler getOperationHandler(AgentRuntime runtime) {
        return request -> {
            Optional<OperationRecord> op = runtime.getOperation(request.pathParam("id"));
            return op.isPresent()
                    ? HelmHttpResponse.ok(toJson(op.get()))
                    : HttpErrors.errorResponse(404, "NOT_FOUND", "operation not found", Map.of());
        };
    }

    static HelmHttpHandler getOperationEventsHandler(AgentRuntime runtime) {
        return request -> {
            List<RuntimeEventRecord> events = runtime.getOperationEvents(request.pathParam("id"));
            return HelmHttpResponse.ok(toJson(Map.of("events", events)));
        };
    }

    static HelmHttpHandler sessionOperationsHandler(AgentRuntime runtime) {
        return request -> {
            String sessionId = request.pathParam("id");
            List<OperationRecord> ops = runtime.listOperations().stream()
                    .filter(op -> sessionId.equals(op.sessionId()))
                    .toList();
            return HelmHttpResponse.ok(toJson(Map.of("operations", ops)));
        };
    }

    static HelmHttpHandler getRunHandler(WorkflowRuntime runtime) {
        return request -> {
            Optional<WorkflowRunRecord> run = runtime.getRun(request.pathParam("id"));
            return run.isPresent()
                    ? HelmHttpResponse.ok(toJson(run.get()))
                    : HttpErrors.errorResponse(404, "NOT_FOUND", "workflow run not found", Map.of());
        };
    }

    static HelmHttpHandler workflowRunsHandler(WorkflowRuntime runtime) {
        return request -> {
            String workflow = request.pathParam("workflow");
            List<WorkflowRunRecord> runs = runtime.listRuns().stream()
                    .filter(r -> workflow.equals(r.workflowName()))
                    .toList();
            return HelmHttpResponse.ok(toJson(Map.of("runs", runs)));
        };
    }

    private static String readField(String body, String field) {
        return readField(readObject(body), field);
    }

    private static String readField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            throw new ValidationException("missing field: " + field, Map.of("field", field), Map.of());
        }
        if (!value.isTextual()) {
            // Reject non-string JSON values so {"text":123} or {"text":[]} fail validation instead of coercing to
            // "123"/"" and silently driving an operation with wrong-shape input.
            throw new ValidationException(
                    "field must be a string: " + field,
                    Map.of("field", field, "nodeType", value.getNodeType().toString()),
                    Map.of());
        }
        return value.asText();
    }

    private static JsonNode readObject(String body) {
        try {
            JsonNode node = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
            if (!node.isObject()) {
                throw new ValidationException("request body must be a JSON object", Map.of(), Map.of());
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ValidationException("invalid JSON body", Map.of(), Map.of("message", e.getMessage()));
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
