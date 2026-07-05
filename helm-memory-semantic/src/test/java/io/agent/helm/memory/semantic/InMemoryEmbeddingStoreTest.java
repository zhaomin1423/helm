package io.agent.helm.memory.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.memory.MemoryRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryEmbeddingStoreTest {

    private static final Instant T1 = Instant.ofEpochSecond(1000);
    private static final Instant T2 = Instant.ofEpochSecond(2000);
    private final FakeEmbeddingProvider provider = new FakeEmbeddingProvider();

    @Test
    void searchReturnsEmptyForUnknownScope() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        MemoryRecord record = new MemoryRecord("m1", "scope-a", "s", "content", T1);
        store.index(record);
        store.store("m1", "scope-a", provider.embed("content"));

        assertThat(store.search("scope-b", provider.embed("content"), 5)).isEmpty();
    }

    @Test
    void searchReturnsTopKOrderedBySimilarity() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        MemoryRecord shipping = new MemoryRecord("m1", "scope-a", "shipping", "express shipping", T1);
        MemoryRecord delivery = new MemoryRecord("m2", "scope-a", "delivery", "delivery speed", T2);

        store.index(shipping);
        store.index(delivery);
        store.store(shipping.id(), shipping.scopeId(), provider.embed(shipping.subject() + " " + shipping.content()));
        store.store(delivery.id(), delivery.scopeId(), provider.embed(delivery.subject() + " " + delivery.content()));

        float[] query = provider.embed("fast delivery");
        // Both memories share synonyms with the query (fast/express, delivery/shipping);
        // m1 ranks above m2 because it matches both synonym axes.
        assertThat(store.search("scope-a", query, 5))
                .extracting(MemoryRecord::id)
                .containsExactly("m1", "m2");
        assertThat(store.search("scope-a", query, 1))
                .extracting(MemoryRecord::id)
                .containsExactly("m1");
    }

    @Test
    void searchExcludesZeroSimilarityEntries() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        MemoryRecord shipping = new MemoryRecord("m1", "scope-a", "shipping", "express shipping", T1);
        MemoryRecord unrelated = new MemoryRecord("m2", "scope-a", "billing", "Invoice billing 30 days", T2);

        store.index(shipping);
        store.index(unrelated);
        store.store(shipping.id(), shipping.scopeId(), provider.embed(shipping.subject() + " " + shipping.content()));
        store.store(
                unrelated.id(), unrelated.scopeId(), provider.embed(unrelated.subject() + " " + unrelated.content()));

        float[] query = provider.embed("fast delivery");
        // m2 shares no tokens/synonyms with the query (cosine 0) and is excluded.
        assertThat(store.search("scope-a", query, 5))
                .extracting(MemoryRecord::id)
                .containsExactly("m1");
    }

    @Test
    void searchSkipsUnindexedRecords() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        // store vector but never index the record
        store.store("m1", "scope-a", provider.embed("hello world"));

        assertThat(store.search("scope-a", provider.embed("hello world"), 5)).isEmpty();
    }

    @Test
    void deleteRemovesVectorAndRecord() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        MemoryRecord record = new MemoryRecord("m1", "scope-a", "s", "content", T1);
        store.index(record);
        store.store("m1", "scope-a", provider.embed("content"));

        store.delete("m1");

        assertThat(store.size()).isZero();
        assertThat(store.search("scope-a", provider.embed("content"), 5)).isEmpty();
    }

    @Test
    void storeReplacesExistingVectorForSameId() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        store.index(new MemoryRecord("m1", "scope-a", "s", "old content", T1));
        store.store("m1", "scope-a", provider.embed("old content"));
        store.index(new MemoryRecord("m1", "scope-a", "s", "new content", T1));
        store.store("m1", "scope-a", provider.embed("new content"));

        assertThat(store.size()).isEqualTo(1);
        var result = store.search("scope-a", provider.embed("new content"), 5);
        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
        assertThat(result.get(0).content()).isEqualTo("new content");
    }

    @Test
    void searchWithZeroOrNegativeTopKReturnsEmpty() {
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        store.index(new MemoryRecord("m1", "scope-a", "s", "content", T1));
        store.store("m1", "scope-a", provider.embed("content"));

        assertThat(store.search("scope-a", provider.embed("content"), 0)).isEmpty();
        assertThat(store.search("scope-a", provider.embed("content"), -1)).isEmpty();
    }
}
