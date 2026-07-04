package io.agent.helm.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.RuntimeStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies session/operation/workflow-run/event history survives a process "restart" (file-mode H2). */
class JdbcRuntimeStoreRestartTest {

    @Test
    void operationSurvivesReconnect(@TempDir Path tempDir) {
        String url = "jdbc:h2:file:" + tempDir.resolve("helm").toAbsolutePath();

        JdbcConnectionPool first = JdbcConnectionPool.create(url, "sa", "");
        HelmSchema.migrate(first);
        RuntimeStore storeOne = new JdbcRuntimeStore(first);
        storeOne.saveOperation(new OperationRecord(
                "op_restart",
                "s1",
                "PROMPT",
                OperationStatus.SUCCEEDED,
                "in",
                "out",
                Map.of(),
                Instant.ofEpochSecond(1000),
                Instant.ofEpochSecond(2000)));
        first.dispose();

        JdbcConnectionPool second = JdbcConnectionPool.create(url, "sa", "");
        HelmSchema.migrate(second);
        RuntimeStore storeTwo = new JdbcRuntimeStore(second);
        try {
            assertThat(storeTwo.loadOperation("op_restart")).isPresent().hasValueSatisfying(op -> {
                assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
                assertThat(op.output()).isEqualTo("out");
            });
        } finally {
            second.dispose();
        }
    }
}
