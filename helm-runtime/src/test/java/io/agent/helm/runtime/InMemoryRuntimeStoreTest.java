package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InMemoryRuntimeStoreTest {
    @Test
    void eventsForOperationAreReturnedBySequence() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendEvent(new RuntimeEventRecord("evt_2", "op_1", null, 2, "second", Map.of(), Instant.now()));
        store.appendEvent(new RuntimeEventRecord("evt_1", "op_1", null, 1, "first", Map.of(), Instant.now()));

        assertThat(store.eventsForOperation("op_1"))
                .extracting(RuntimeEventRecord::type)
                .containsExactly("first", "second");
    }
}
