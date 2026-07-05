package io.agent.helm.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract tests for {@link MemoryStore}. Store adapters extend this class and implement
 * {@link #createStore()}. Both the in-memory and JDBC implementations must pass.
 */
public abstract class MemoryStoreContractTest {
    private static final Instant T1 = Instant.ofEpochSecond(1000);
    private static final Instant T2 = Instant.ofEpochSecond(2000);

    protected abstract MemoryStore createStore();

    @Test
    void saveAndLoadMemory() {
        MemoryStore store = createStore();
        MemoryRecord memory = new MemoryRecord("m1", "agent:inst", "prefs", "User prefers Java", T1);

        store.save(memory);

        assertThat(store.load("m1")).contains(memory);
    }

    @Test
    void loadMissingMemoryReturnsEmpty() {
        assertThat(createStore().load("nope")).isEmpty();
    }

    @Test
    void listReturnsScopeMemoriesSortedByCreatedAtAscending() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m2", "scope-a", "s2", "second", T2));
        store.save(new MemoryRecord("m1", "scope-a", "s1", "first", T1));
        store.save(new MemoryRecord("m3", "scope-b", "s3", "other scope", T1));

        assertThat(store.list("scope-a")).extracting(MemoryRecord::id).containsExactly("m1", "m2");
    }

    @Test
    void listUnknownScopeReturnsEmpty() {
        assertThat(createStore().list("nope")).isEmpty();
    }

    @Test
    void searchMatchesSubjectOrContentCaseInsensitively() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "language", "User prefers Java", T1));
        store.save(new MemoryRecord("m2", "scope-a", "Timezone", "UTC+8", T2));
        store.save(new MemoryRecord("m3", "scope-b", "language", "User prefers Java", T1));

        assertThat(store.search("scope-a", "java")).extracting(MemoryRecord::id).containsExactly("m1");
        assertThat(store.search("scope-a", "timezone"))
                .extracting(MemoryRecord::id)
                .containsExactly("m2");
        assertThat(store.search("scope-a", "missing")).isEmpty();
    }

    @Test
    void saveReplacesExistingMemory() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "old", T1));
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "new", T1));

        assertThat(store.load("m1"))
                .hasValueSatisfying(memory -> assertThat(memory.content()).isEqualTo("new"));
        assertThat(store.list("scope-a")).hasSize(1);
    }

    @Test
    void deleteRemovesMemory() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "content", T1));

        store.delete("m1");

        assertThat(store.load("m1")).isEmpty();
        assertThat(store.list("scope-a")).isEmpty();
    }
}
