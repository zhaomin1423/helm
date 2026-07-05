package io.agent.helm.memory.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agent.helm.core.memory.EmbeddingProvider;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.runtime.memory.InMemoryMemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SemanticMemoryStoreTest {

    private static final Instant T1 = Instant.ofEpochSecond(1000);
    private static final Instant T2 = Instant.ofEpochSecond(2000);

    private SemanticMemoryStore createStore() {
        return new SemanticMemoryStore(
                new InMemoryMemoryStore(), new FakeEmbeddingProvider(), new InMemoryEmbeddingStore(), 5);
    }

    @Test
    void saveThenSearchReturnsSemanticallyRelatedMemory() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "Customer prefers express shipping", T1));
        store.save(new MemoryRecord("m2", "scope-a", "billing", "Invoice billing 30 days", T2));

        // "fast delivery" shares no keyword with "express shipping" but FakeEmbeddingProvider maps
        // fast->express and delivery->shipping as synonyms, so m1 ranks first; m2 has zero
        // similarity and is excluded.
        List<MemoryRecord> result = store.search("scope-a", "fast delivery");

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).id()).isEqualTo("m1");
    }

    @Test
    void searchRespectsTopK() {
        SemanticMemoryStore store = new SemanticMemoryStore(
                new InMemoryMemoryStore(), new FakeEmbeddingProvider(), new InMemoryEmbeddingStore(), 1);
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "express shipping", T1));
        store.save(new MemoryRecord("m2", "scope-a", "delivery", "delivery speed", T2));

        // Both memories are semantically related to "fast delivery" but topK=1 limits the result.
        List<MemoryRecord> result = store.search("scope-a", "fast delivery");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("m1");
    }

    @Test
    void searchFallsBackToKeywordWhenNoVectorsStored() {
        // Save via delegate directly (bypasses SemanticMemoryStore.save -> no vector stored),
        // simulating memories persisted before semantic retrieval was enabled.
        MemoryStore delegate = new InMemoryMemoryStore();
        SemanticMemoryStore store =
                new SemanticMemoryStore(delegate, new FakeEmbeddingProvider(), new InMemoryEmbeddingStore(), 5);
        delegate.save(new MemoryRecord("m1", "scope-a", "language", "User prefers Java", T1));

        List<MemoryRecord> result = store.search("scope-a", "java");

        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void searchFallsBackToKeywordWhenEmbeddingProviderThrows() {
        SemanticMemoryStore store = new SemanticMemoryStore(
                new InMemoryMemoryStore(), new ThrowingEmbeddingProvider(), new InMemoryEmbeddingStore(), 5);
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "User prefers Java", T1));

        List<MemoryRecord> result = store.search("scope-a", "java");

        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void searchFallsBackToKeywordWhenProviderAndStoreAreNull() {
        SemanticMemoryStore store = new SemanticMemoryStore(new InMemoryMemoryStore(), null, null, 5);
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "User prefers Java", T1));

        List<MemoryRecord> result = store.search("scope-a", "java");

        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void loadDelegatesToUnderlyingStore() {
        SemanticMemoryStore store = createStore();
        MemoryRecord memory = new MemoryRecord("m1", "scope-a", "prefs", "content", T1);
        store.save(memory);

        Optional<MemoryRecord> loaded = store.load("m1");
        assertThat(loaded).contains(memory);
        assertThat(store.load("missing")).isEmpty();
    }

    @Test
    void listDelegatesToUnderlyingStore() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m2", "scope-a", "s2", "second", T2));
        store.save(new MemoryRecord("m1", "scope-a", "s1", "first", T1));

        assertThat(store.list("scope-a")).extracting(MemoryRecord::id).containsExactly("m1", "m2");
    }

    @Test
    void deleteRemovesRecordAndVector() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "content", T1));

        store.delete("m1");

        assertThat(store.load("m1")).isEmpty();
        assertThat(store.list("scope-a")).isEmpty();
        // search also returns nothing (no vector, keyword fallback finds no record).
        assertThat(store.search("scope-a", "content")).isEmpty();
    }

    @Test
    void saveReplacesExistingRecordAndVector() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "User prefers Java", T1));
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "User prefers Python", T1));

        assertThat(store.list("scope-a")).hasSize(1);
        assertThat(store.load("m1"))
                .hasValueSatisfying(m -> assertThat(m.content()).isEqualTo("User prefers Python"));
        // The vector was re-embedded for the updated content; searching the new content returns m1.
        List<MemoryRecord> result = store.search("scope-a", "User prefers Python");
        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void searchIsScopedByScopeId() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "express shipping", T1));
        store.save(new MemoryRecord("m2", "scope-b", "shipping", "express shipping", T1));

        List<MemoryRecord> result = store.search("scope-a", "fast delivery");
        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void embedsSubjectAndContentTogether() {
        SemanticMemoryStore store = createStore();
        // The subject carries the semantic signal; the content is unrelated. save embeds
        // subject + " " + content, so the subject's tokens still drive the match.
        store.save(new MemoryRecord("m1", "scope-a", "fast delivery", "unrelated content xyz", T1));

        List<MemoryRecord> result = store.search("scope-a", "fast delivery");
        assertThat(result.get(0).id()).isEqualTo("m1");
    }

    @Test
    void searchReturnsEmptyForUnknownScope() {
        SemanticMemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "express shipping", T1));

        assertThat(store.search("scope-b", "fast delivery")).isEmpty();
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() ->
                        new SemanticMemoryStore(null, new FakeEmbeddingProvider(), new InMemoryEmbeddingStore(), 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate");
    }

    @Test
    void constructorRejectsNonPositiveTopK() {
        assertThatThrownBy(() -> new SemanticMemoryStore(new InMemoryMemoryStore(), null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> new SemanticMemoryStore(new InMemoryMemoryStore(), null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsMismatchedProviderAndStore() {
        assertThatThrownBy(
                        () -> new SemanticMemoryStore(new InMemoryMemoryStore(), new FakeEmbeddingProvider(), null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddingProvider and embeddingStore");
        assertThatThrownBy(
                        () -> new SemanticMemoryStore(new InMemoryMemoryStore(), null, new InMemoryEmbeddingStore(), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Embedding provider that always fails, to exercise the keyword-fallback path. */
    private static final class ThrowingEmbeddingProvider implements EmbeddingProvider {
        @Override
        public float[] embed(String text) {
            throw new RuntimeException("embedding unavailable");
        }

        @Override
        public int dimension() {
            return FakeEmbeddingProvider.DIMENSION;
        }
    }
}
