package io.agent.helm.core.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent operation records. Sub-interface of {@link RuntimeStore}.
 *
 * <p>Pagination overloads accept a {@code limit} (and optionally an {@code after} cursor) argument. Adapters MUST honor
 * a sane default cap (e.g. 1000) even when {@code limit} is huge, to avoid unbounded result sets.
 */
public interface OperationStore {
    void saveOperation(OperationRecord operation);

    Optional<OperationRecord> loadOperation(String operationId);

    /**
     * Lists operations, ordered by {@code createdAt} ascending.
     *
     * @param limit the maximum number of records to return; adapters MUST honor a sane default cap (e.g. 1000) even
     *     when {@code limit} is huge.
     */
    List<OperationRecord> listOperations(int limit);

    /**
     * Lists operations created at or after {@code after}, ordered by {@code createdAt} ascending.
     *
     * @param after the lower-bound (inclusive) cursor on {@code createdAt}; may be {@code null} to start from the
     *     oldest record.
     * @param limit the maximum number of records to return; adapters MUST honor a sane default cap (e.g. 1000) even
     *     when {@code limit} is huge.
     */
    List<OperationRecord> listOperations(Instant after, int limit);

    /** Convenience overload; equivalent to {@link #listOperations(int) listOperations(Integer.MAX_VALUE)}. */
    default List<OperationRecord> listOperations() {
        return listOperations(Integer.MAX_VALUE);
    }
}
