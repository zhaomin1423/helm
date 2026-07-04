package io.agent.helm.runtime;

import io.agent.helm.core.store.OperationStatus;

public record OperationHandle(String operationId, OperationStatus status) {}
