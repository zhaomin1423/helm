package io.agent.helm.core.workflow;

import io.agent.helm.core.agent.AgentHarnessApi;
import java.time.Clock;
import java.util.Map;

/**
 * Execution context handed to {@link WorkflowDefinition#run}. Exposes the workflow input, the agent harness for
 * dispatching prompts/tools, the id of the operation that drove this run, a {@link Clock} for deterministic time, and
 * an optional {@link #event(String, Map) event} sink for emitting structured progress events during the run.
 *
 * @param <I> the workflow input type.
 */
public interface WorkflowContext<I> {
    /** The structured input passed to {@link WorkflowDefinition#run}. */
    I input();

    /** The agent harness for dispatching prompts and tool calls. */
    AgentHarnessApi harness();

    /** The id of the operation that drove this workflow run; never {@code null}. */
    String operationId();

    /** The runtime clock; never {@code null}. Workflows should use this for deterministic time. */
    Clock clock();

    /**
     * Optional structured event sink. Implementations may record the event or treat this as a no-op. The runtime
     * normalizes the {@code type} and {@code details} into a {@link io.agent.helm.core.event.RuntimeEventRecord}.
     *
     * @param type a stable event type identifier; never {@code null}.
     * @param details structured key/value pairs; may be {@code null}.
     */
    default void event(String type, Map<String, Object> details) {
        // no-op by default; the runtime overrides this to record events.
    }
}
