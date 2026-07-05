package io.agent.helm.core.security;

/**
 * Stable actions the runtime admits; each maps to a {@code AgentRuntime}/{@code WorkflowRuntime} entry point. When the
 * action targets a named resource, the {@link HelmResource} carries the relevant identifier:
 *
 * <ul>
 *   <li>{@link #TOOL_EXECUTE} — the resource name is the tool name being invoked.
 *   <li>{@link #MEMORY_WRITE} — the resource name is the memory scope being written.
 *   <li>{@link #SANDBOX_COMMAND} — the resource name is the command argv[0] being executed.
 * </ul>
 */
public enum HelmAction {
    PROMPT,
    DISPATCH,
    WORKFLOW_INVOKE,
    READ_OPERATION,
    LIST_OPERATIONS,
    READ_EVENTS,
    LIST_SESSIONS,
    READ_SESSION,
    RESET_SESSION,
    READ_WORKFLOW_RUN,
    LIST_WORKFLOW_RUNS,
    TOOL_EXECUTE,
    MEMORY_WRITE,
    SANDBOX_COMMAND
}
