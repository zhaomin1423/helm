package io.agent.helm.persistence.jdbc;

import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.RuntimeStoreContractTest;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcConnectionPool;

/** Runs {@link RuntimeStoreContractTest} against {@link JdbcRuntimeStore} on H2 in-memory. */
class JdbcRuntimeStoreContractTest extends RuntimeStoreContractTest {
    private static final AtomicLong COUNTER = new AtomicLong();

    @Override
    protected RuntimeStore createStore() {
        JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:helm-contract-" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        HelmSchema.migrate(pool);
        return new JdbcRuntimeStore(pool);
    }
}
