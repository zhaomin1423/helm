package io.agent.helm.core.store;

import java.util.List;
import java.util.Optional;

/** Agent operation records. Sub-interface of {@link RuntimeStore}. */
public interface OperationStore {
    void saveOperation(OperationRecord operation);

    Optional<OperationRecord> loadOperation(String operationId);

    List<OperationRecord> listOperations();
}
