package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.memory.MemoryStoreContractTest;

/**
 * Runs the shared {@link MemoryStoreContractTest} against {@link SemanticMemoryStore}, verifying the decorator
 * preserves the base {@link MemoryStore} semantics (save/load/list/search/delete).
 */
final class SemanticMemoryStoreBackwardCompatTest extends MemoryStoreContractTest {

    @Override
    protected MemoryStore createStore() {
        return new SemanticMemoryStore(
                new InMemoryMemoryStore(), new FakeEmbeddingProvider(), new InMemoryEmbeddingStore(), 5);
    }
}
