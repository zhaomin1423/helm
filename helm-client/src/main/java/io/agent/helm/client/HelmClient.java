package io.agent.helm.client;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.annotation.Preview;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.runtime.OperationHandle;
import io.agent.helm.runtime.WorkflowRunHandle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * Synchronous Java client for the Helm HTTP routes defined in {@code helm-http-core}. Method names and parameter
 * semantics mirror {@code AgentRuntime}/{@code WorkflowRuntime}; HTTP details, error mapping, retry, and SSE parsing
 * are encapsulated by the implementation.
 *
 * <p>Error responses ({@code {"error":{code,message,details}}}) are restored to
 * {@link io.agent.helm.core.error.HelmException} subclasses with code/details pass-through. {@code 404 NOT_FOUND} on
 * {@link #getOperation(String)} / {@link #getRun(String)} / {@link #getSession(String)} is mapped to
 * {@link Optional#empty()} rather than thrown.
 *
 * <p>The client owns an underlying {@link java.net.http.HttpClient} with a connection pool and executor thread.
 * Long-lived callers should reuse a single {@code HelmClient} instance and {@link #close()} it when done to release
 * those resources; short-lived callers can use try-with-resources:
 *
 * <pre>{@code
 * try (HelmClient client = HelmClient.builder().baseUrl(baseUrl).build()) {
 *     client.prompt("echo", "i1", "s1", "hi");
 * }
 * }</pre>
 */
public interface HelmClient extends AutoCloseable {

    // —— Agent ——

    PromptResult prompt(String agent, String instance, String session, String text);

    OperationHandle dispatch(String agent, AgentDispatchRequest request);

    Optional<OperationRecord> getOperation(String operationId);

    List<RuntimeEventRecord> getOperationEvents(String operationId);

    List<OperationRecord> sessionOperations(String sessionId);

    // —— Session (depend on routes being added in M6/M7) ——

    @Preview
    List<AgentSessionState> listSessions();

    @Preview
    Optional<AgentSessionState> getSession(String sessionId);

    @Preview
    void resetSession(String sessionId);

    // —— Workflow ——

    /**
     * Invokes a workflow and deserializes the result into the given type.
     *
     * @param workflow workflow name (URL path segment).
     * @param input workflow input payload.
     * @param outputType {@link Class} describing the result type; the JSON result node is deserialized into this type.
     */
    <I, O> WorkflowRunHandle<O> invokeWorkflow(String workflow, I input, Class<O> outputType);

    /**
     * Invokes a workflow and deserializes the result into the given type. Use this overload when the result type is
     * generic (e.g. {@code List<Foo>}); the {@link TypeReference} preserves the full generic signature.
     *
     * @param workflow workflow name (URL path segment).
     * @param input workflow input payload.
     * @param outputType {@link TypeReference} describing the result type.
     */
    <I, O> WorkflowRunHandle<O> invokeWorkflow(String workflow, I input, TypeReference<O> outputType);

    Optional<WorkflowRunRecord> getRun(String runId);

    List<WorkflowRunRecord> workflowRuns(String workflow);

    // —— Streaming ——

    /**
     * Streams prompt events from {@code POST /agents/{agent}/instances/{instance}/sessions/{session}/prompt/stream}.
     * Events are delivered incrementally as SSE frames arrive; subscribers receive the first event before the body is
     * fully buffered. @Preview the streaming route is planned but not yet registered by {@code HelmHttpRoutes}; the SSE
     * parsing surface is being validated.
     */
    @Preview
    Flow.Publisher<PromptStreamEvent> promptStream(String agent, String instance, String session, String text);

    /** Releases the underlying HTTP client, connection pool, and executor. Idempotent. */
    @Override
    void close();

    static HelmClientBuilder builder() {
        return new HelmClientBuilder();
    }
}
