package io.agent.helm.persistence.jdbc;

import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC {@link MemoryStore}. Uses the {@code helm_memory} table created by the {@code V2__memory}
 * migration. SQL errors are mapped to {@link PersistenceException}.
 */
public final class JdbcMemoryStore implements MemoryStore {
    private final DataSource dataSource;

    public JdbcMemoryStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void save(MemoryRecord memory) {
        execute(
                "MERGE INTO helm_memory (id, scope_id, subject, content, created_at) KEY (id) VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, memory.id());
                    ps.setString(2, memory.scopeId());
                    ps.setString(3, memory.subject());
                    ps.setString(4, memory.content());
                    ps.setTimestamp(5, Timestamp.from(memory.createdAt()));
                });
    }

    @Override
    public Optional<MemoryRecord> load(String memoryId) {
        List<MemoryRecord> result = queryList(
                "SELECT id, scope_id, subject, content, created_at FROM helm_memory WHERE id = ?",
                ps -> ps.setString(1, memoryId));
        return result.stream().findFirst();
    }

    @Override
    public List<MemoryRecord> list(String scopeId) {
        return queryList(
                "SELECT id, scope_id, subject, content, created_at FROM helm_memory WHERE scope_id = ? ORDER BY created_at ASC",
                ps -> ps.setString(1, scopeId));
    }

    @Override
    public List<MemoryRecord> search(String scopeId, String query) {
        String needle = "%" + query.toLowerCase(Locale.ROOT) + "%";
        return queryList(
                "SELECT id, scope_id, subject, content, created_at FROM helm_memory WHERE scope_id = ? AND (LOWER(subject) LIKE ? OR LOWER(content) LIKE ?) ORDER BY created_at ASC",
                ps -> {
                    ps.setString(1, scopeId);
                    ps.setString(2, needle);
                    ps.setString(3, needle);
                });
    }

    @Override
    public void delete(String memoryId) {
        execute("DELETE FROM helm_memory WHERE id = ?", ps -> ps.setString(1, memoryId));
    }

    private List<MemoryRecord> queryList(String sql, SqlConsumer<PreparedStatement> setter) {
        List<MemoryRecord> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            setter.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MemoryRecord(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            toInstant(rs.getTimestamp(5))));
                }
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        }
        return result;
    }

    private void execute(String sql, SqlConsumer<PreparedStatement> setter) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            setter.accept(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw toHelmException(e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static PersistenceException toHelmException(SQLException e) {
        return new PersistenceException(
                "persistence error",
                Map.of("sqlState", String.valueOf(e.getSQLState())),
                Map.of("message", String.valueOf(e.getMessage())));
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }
}
