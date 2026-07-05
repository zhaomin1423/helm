package io.agent.helm.core.store;

/**
 * Aggregate facade over the four store sub-interfaces. Existing adapters continue to {@code implements RuntimeStore};
 * future adapters may implement only the sub-interface they need (e.g. an event sink implements {@link EventStore}
 * alone). See {@code docs/contracts/runtime-store.md}.
 */
public interface RuntimeStore extends SessionStore, OperationStore, WorkflowRunStore, EventStore {}
