package io.agent.helm.runtime.memory;

import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.memory.MemoryStoreContractTest;

/** Runs {@link MemoryStoreContractTest} against {@link InMemoryMemoryStore}. */
final class InMemoryMemoryStoreContractTest extends MemoryStoreContractTest {
    @Override
    protected MemoryStore createStore() {
        return new InMemoryMemoryStore();
    }
}
