package io.agent.helm.client;

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
 */
public interface HelmClient {

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

    <I, O> WorkflowRunHandle<O> invokeWorkflow(String workflow, I input);

    Optional<WorkflowRunRecord> getRun(String runId);

    List<WorkflowRunRecord> workflowRuns(String workflow);

    // —— Streaming ——

    /**
     * Streams prompt events from {@code POST
     * /agents/{agent}/instances/{instance}/sessions/{session}/prompt/stream}. @Preview the streaming route is planned
     * but not yet registered by {@code HelmHttpRoutes}; the SSE parsing surface is being validated.
     */
    @Preview
    Flow.Publisher<PromptStreamEvent> promptStream(String agent, String instance, String session, String text);

    static HelmClientBuilder builder() {
        return new HelmClientBuilder();
    }
}
