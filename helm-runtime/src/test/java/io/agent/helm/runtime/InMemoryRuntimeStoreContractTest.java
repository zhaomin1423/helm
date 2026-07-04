package io.agent.helm.runtime;

import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.RuntimeStoreContractTest;

/** Runs {@link RuntimeStoreContractTest} against {@link InMemoryRuntimeStore}. */
final class InMemoryRuntimeStoreContractTest extends RuntimeStoreContractTest {
    @Override
    protected RuntimeStore createStore() {
        return new InMemoryRuntimeStore();
    }
}
