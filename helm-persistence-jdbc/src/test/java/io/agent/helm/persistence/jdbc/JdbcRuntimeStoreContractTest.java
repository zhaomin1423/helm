package io.agent.helm.persistence.jdbc;

import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.RuntimeStoreContractTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;

/** Runs {@link RuntimeStoreContractTest} against {@link JdbcRuntimeStore} on H2 in-memory. */
class JdbcRuntimeStoreContractTest extends RuntimeStoreContractTest {
    private static final AtomicLong COUNTER = new AtomicLong();

    // Each createStore() call spins up a fresh H2 database for test isolation. The pools are disposed after each test
    // so the connection pool resources are released (the previous code leaked one pool per createStore() call).
    private final List<JdbcConnectionPool> pools = new ArrayList<>();

    @Override
    protected RuntimeStore createStore() {
        JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:helm-contract-" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        HelmSchema.migrate(pool);
        pools.add(pool);
        return new JdbcRuntimeStore(pool);
    }

    @AfterEach
    void disposePools() {
        pools.forEach(JdbcConnectionPool::dispose);
        pools.clear();
    }
}
