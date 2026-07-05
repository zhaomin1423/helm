package io.agent.helm.persistence.jdbc;

import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.memory.MemoryStoreContractTest;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcConnectionPool;

/** Runs {@link MemoryStoreContractTest} against {@link JdbcMemoryStore} on H2 in-memory. */
class JdbcMemoryStoreContractTest extends MemoryStoreContractTest {
    private static final AtomicLong COUNTER = new AtomicLong();

    @Override
    protected MemoryStore createStore() {
        JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:helm-memory-contract-" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        HelmSchema.migrate(pool);
        return new JdbcMemoryStore(pool);
    }
}
