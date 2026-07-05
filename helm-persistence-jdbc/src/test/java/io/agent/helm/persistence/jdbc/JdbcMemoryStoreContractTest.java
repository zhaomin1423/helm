package io.agent.helm.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.memory.MemoryStoreContractTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs {@link MemoryStoreContractTest} against {@link JdbcMemoryStore} on H2 in-memory. */
class JdbcMemoryStoreContractTest extends MemoryStoreContractTest {
    private static final AtomicLong COUNTER = new AtomicLong();

    private final List<JdbcConnectionPool> pools = new ArrayList<>();

    @Override
    protected MemoryStore createStore() {
        JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:helm-memory-contract-" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        HelmSchema.migrate(pool);
        pools.add(pool);
        return new JdbcMemoryStore(pool);
    }

    @AfterEach
    void disposePools() {
        pools.forEach(JdbcConnectionPool::dispose);
        pools.clear();
    }

    /**
     * LIKE wildcard characters in the query must be treated literally: searching for {@code 50%} must not match
     * {@code 1500 off} (the {@code %} is a literal percent, not a wildcard).
     */
    @Test
    void likeWildcardsAreEscapedAndMatchedLiterally() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope", "discount", "50% off", Instant.ofEpochSecond(1)));
        store.save(new MemoryRecord("m2", "scope", "price", "1500 off", Instant.ofEpochSecond(2)));

        assertThat(store.search("scope", "50%")).extracting(MemoryRecord::id).containsExactly("m1");
        assertThat(store.search("scope", "1500")).extracting(MemoryRecord::id).containsExactly("m2");
        // Underscore wildcard is also literal.
        store.save(new MemoryRecord("m3", "scope", "code", "a_b", Instant.ofEpochSecond(3)));
        assertThat(store.search("scope", "a_b")).extracting(MemoryRecord::id).containsExactly("m3");
    }
}
