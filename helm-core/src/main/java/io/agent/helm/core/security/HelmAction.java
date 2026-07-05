package io.agent.helm.core.security;

/** Stable actions the runtime admits; each maps to a {@code AgentRuntime}/{@code WorkflowRuntime} entry point. */
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
    LIST_WORKFLOW_RUNS
}
