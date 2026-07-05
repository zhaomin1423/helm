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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC {@link MemoryStore}. Uses the {@code helm_memory} table created by the {@code V2__memory} migration.
 *
 * <p><b>Connection / auto-commit</b>: each call acquires a fresh connection from {@link DataSource} and explicitly sets
 * {@code autoCommit=true}. A connection pool (e.g. {@code JdbcConnectionPool}) is the expected backing. SQL errors are
 * mapped to {@link PersistenceException} with the {@link SQLException} attached as cause.
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

    /**
     * Case-insensitive substring search on {@code subject} and {@code content}. Characters that are LIKE wildcards in
     * the query ({@code %}, {@code _}) and the escape character ({@code \}) are escaped so that a literal search for
     * {@code 50%} does not match {@code 1500 off}. The {@code ESCAPE '\'} clause declares the escape character.
     */
    @Override
    public List<MemoryRecord> search(String scopeId, String query) {
        String escaped = escapeLike(query == null ? "" : query);
        String needle = "%" + escaped + "%";
        return queryList(
                "SELECT id, scope_id, subject, content, created_at FROM helm_memory "
                        + "WHERE scope_id = ? AND (LOWER(subject) LIKE ? ESCAPE '\\' OR LOWER(content) LIKE ? ESCAPE '\\') "
                        + "ORDER BY created_at ASC",
                ps -> {
                    ps.setString(1, scopeId);
                    ps.setString(2, needle.toLowerCase(Locale.ROOT));
                    ps.setString(3, needle.toLowerCase(Locale.ROOT));
                });
    }

    /** Escapes {@code \}, {@code %}, and {@code _} so they match literally under a LIKE with {@code ESCAPE '\'}. */
    private static String escapeLike(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    @Override
    public void delete(String memoryId) {
        execute("DELETE FROM helm_memory WHERE id = ?", ps -> ps.setString(1, memoryId));
    }

    private List<MemoryRecord> queryList(String sql, SqlConsumer<PreparedStatement> setter) {
        List<MemoryRecord> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
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
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        }
        return result;
    }

    private void execute(String sql, SqlConsumer<PreparedStatement> setter) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                setter.accept(ps);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Maps a {@link SQLException} to a {@link PersistenceException}, preserving the original exception as the cause and
     * the {@link SQLException#getNextException() next-exception} chain in {@code developerDetails}. Adds a
     * {@code retryable} flag to {@code details} derived from the SQLSTATE class ({@code 40xxx} → retryable,
     * {@code 23xxx} → not retryable). The {@code sqlState} key is omitted from {@code details} when the driver did not
     * supply one (no {@code "null"} string).
     */
    private static PersistenceException toHelmException(SQLException e) {
        String sqlState = e.getSQLState();
        boolean retryable = sqlState != null && sqlState.startsWith("40");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retryable", retryable);
        if (sqlState != null && !sqlState.isEmpty()) {
            details.put("sqlState", sqlState);
        }
        Map<String, Object> developerDetails = new LinkedHashMap<>();
        developerDetails.put("message", String.valueOf(e.getMessage()));
        if (e.getErrorCode() != 0) {
            developerDetails.put("vendorCode", e.getErrorCode());
        }
        SQLException next = e.getNextException();
        if (next != null) {
            List<String> chain = new ArrayList<>();
            SQLException cur = next;
            while (cur != null && chain.size() < 8) {
                chain.add(String.valueOf(cur.getSQLState()) + ": " + String.valueOf(cur.getMessage()));
                cur = cur.getNextException();
            }
            developerDetails.put("nextExceptions", chain);
        }
        return new PersistenceException("persistence error", details, developerDetails, e);
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }
}
