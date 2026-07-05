package io.agent.helm.core.workflow;

import io.agent.helm.core.type.TypeDescriptor;

/**
 * A workflow definition: a named, typed procedure that orchestrates agent harness calls. The runtime invokes
 * {@link #run} within a {@link WorkflowContext} that supplies the input, harness, operation id, and clock.
 *
 * <p><b>Cancellation:</b> the runtime may interrupt the thread executing {@link #run} to cancel a workflow.
 * Implementations should propagate {@link InterruptedException} (declare it on {@code run} if blocking), and should
 * periodically check {@link Thread#isInterrupted()} for long-running non-blocking work so cancellation takes effect
 * promptly. Failing to respond to interruption leaves the workflow at the mercy of the runtime's hard timeout.
 */
public interface WorkflowDefinition<I, O> {
    String name();

    WorkflowConfig config();

    TypeDescriptor<I> inputType();

    TypeDescriptor<O> outputType();

    /**
     * Runs the workflow.
     *
     * @param context the execution context; never {@code null}.
     * @throws InterruptedException if the thread was interrupted while blocked; the runtime cancels the run.
     * @throws Exception if the workflow fails for any other reason.
     */
    O run(WorkflowContext<I> context) throws Exception;
}
