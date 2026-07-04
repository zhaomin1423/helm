package io.agent.helm.persistence.jdbc;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Runs Helm schema migrations via Flyway. Callers may instead self-manage migrations. */
public final class HelmSchema {
    private HelmSchema() {}

    public static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }
}
