package io.agent.helm.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.core.error.SessionConflictException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.agent.helm.core.message.ContentBlock;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.TextBlock;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.security.HelmAction;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.OperationStatus;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.store.WorkflowRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC {@link RuntimeStore}. Payloads (messages, errors, event payloads) are stored as JSON text columns;
 * {@code helm_operation.input}/{@code output} and {@code helm_workflow_run.input}/{@code output} are stored verbatim as
 * raw JSON strings (the caller is responsible for serializing). Timestamps use {@code TIMESTAMP}.
 *
 * <p><b>Connection / auto-commit</b>: each call acquires a fresh connection from {@link DataSource} and explicitly sets
 * {@code autoCommit=true}. The exception is {@link #saveSession}, which runs a compare-and-swap transaction with
 * {@code autoCommit=false} (UPDATE-then-INSERT) so the OCC check is atomic. Callers MUST NOT pass a {@code DataSource}
 * whose connections are pinned to a single transaction; a connection pool (e.g. {@code JdbcConnectionPool}) is the
 * expected backing.
 *
 * <p>SQL errors are mapped to {@link PersistenceException} with the {@link SQLException} attached as cause; the cause
 * chain (including {@link SQLException#getNextException()}) is preserved in {@code developerDetails}.
 */
public final class JdbcRuntimeStore implements RuntimeStore {
    private static final ObjectMapper MAPPER = createMapper();

    /** Default cap applied to every {@code list*(int)} / {@code eventsFor*(int)} call per the Store SPI contract. */
    private static final int DEFAULT_LIMIT = 1000;

    private final DataSource dataSource;

    public JdbcRuntimeStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    // --- SessionStore (OCC) ---

    @Override
    public Optional<AgentSessionState> loadSession(String id) {
        return queryOne(
                "SELECT agent_name, instance_id, session_name, version, messages, created_at, updated_at FROM helm_session WHERE id = ?",
                ps -> ps.setString(1, id),
                rs -> new AgentSessionState(
                        id,
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        parseMessages(rs.getString(5)),
                        toInstant(rs.getTimestamp(6)),
                        toInstant(rs.getTimestamp(7))));
    }

    /**
     * Persists {@code session} using optimistic concurrency control. For an existing session, the stored
     * {@code version} MUST match {@code session.version()}; the update atomically bumps the stored version. If a row
     * exists with a different version, {@link SessionConflictException} is thrown carrying the stored and requested
     * versions. A new session (no existing row) is inserted directly; a concurrent insert race is reported as a
     * conflict.
     */
    @Override
    public void saveSession(AgentSessionState session) {
        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            try {
                int updated = updateSession(c, session);
                if (updated == 0) {
                    Long storedVersion = selectSessionVersion(c, session.id());
                    if (storedVersion == null) {
                        insertSession(c, session);
                    } else {
                        throw conflict(session.id(), storedVersion, session.version());
                    }
                }
                c.commit();
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(c);
                if (isUniqueViolation(e)) {
                    // A concurrent insert won the race for a new session id — caller must reload and reconcile.
                    throw conflict(session.id(), session.version(), session.version());
                }
                throw toHelmException(e);
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        } finally {
            if (c != null) {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort reset before returning the connection to the pool
                }
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // best-effort close
                }
            }
        }
    }

    private int updateSession(Connection c, AgentSessionState session) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE helm_session SET agent_name = ?, instance_id = ?, session_name = ?, version = version + 1, "
                        + "messages = ?, created_at = ?, updated_at = ? WHERE id = ? AND version = ?")) {
            ps.setString(1, session.agentName());
            ps.setString(2, session.instanceId());
            ps.setString(3, session.sessionName());
            ps.setString(4, toJson(session.messages()));
            ps.setTimestamp(5, toTimestamp(session.createdAt()));
            ps.setTimestamp(6, toTimestamp(session.updatedAt()));
            ps.setString(7, session.id());
            ps.setLong(8, session.version());
            return ps.executeUpdate();
        }
    }

    private Long selectSessionVersion(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT version FROM helm_session WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertSession(Connection c, AgentSessionState session) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO helm_session (id, agent_name, instance_id, session_name, version, messages, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, session.id());
            ps.setString(2, session.agentName());
            ps.setString(3, session.instanceId());
            ps.setString(4, session.sessionName());
            ps.setLong(5, session.version());
            ps.setString(6, toJson(session.messages()));
            ps.setTimestamp(7, toTimestamp(session.createdAt()));
            ps.setTimestamp(8, toTimestamp(session.updatedAt()));
            ps.executeUpdate();
        }
    }

    private static SessionConflictException conflict(String sessionId, long storedVersion, long requestedVersion) {
        return new SessionConflictException(
                "session version conflict for " + sessionId,
                Map.of(
                        "sessionId", sessionId,
                        "storedVersion", storedVersion,
                        "requestedVersion", requestedVersion),
                Map.of());
    }

    @Override
    public List<AgentSessionState> listSessions(int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, agent_name, instance_id, session_name, version, messages, created_at, updated_at FROM helm_session ORDER BY created_at ASC LIMIT ?",
                ps -> ps.setInt(1, effective),
                rs -> new AgentSessionState(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getLong(5),
                        parseMessages(rs.getString(6)),
                        toInstant(rs.getTimestamp(7)),
                        toInstant(rs.getTimestamp(8))));
    }

    @Override
    public void deleteSession(String sessionId) {
        // Application-level cascade (DB-level FKs are deliberately omitted — see V1__init.sql). Purge events for the
        // session's operations, then the operations, then the session, in a single transaction so the delete is atomic.
        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM helm_event WHERE operation_id IN (SELECT id FROM helm_operation WHERE session_id = ?)")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM helm_operation WHERE session_id = ?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM helm_session WHERE id = ?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(c);
                throw toHelmException(e);
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        } finally {
            if (c != null) {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort reset before returning the connection to the pool
                }
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // best-effort close
                }
            }
        }
    }

    // --- OperationStore ---

    @Override
    public Optional<OperationRecord> loadOperation(String id) {
        return queryOne(
                "SELECT session_id, type, status, input, output, error, created_at, completed_at FROM helm_operation WHERE id = ?",
                ps -> ps.setString(1, id),
                rs -> {
                    HelmAction type = parseAction(rs.getString(2));
                    if (type == null) {
                        return null;
                    }
                    return new OperationRecord(
                            id,
                            rs.getString(1),
                            type,
                            OperationStatus.valueOf(rs.getString(3)),
                            rs.getString(4),
                            rs.getString(5),
                            parseMap(rs.getString(6)),
                            toInstant(rs.getTimestamp(7)),
                            toInstant(rs.getTimestamp(8)));
                });
    }

    @Override
    public void saveOperation(OperationRecord operation) {
        execute(
                "MERGE INTO helm_operation (id, session_id, type, status, input, output, error, created_at, completed_at) KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, operation.id());
                    ps.setString(2, operation.sessionId());
                    ps.setString(3, operation.type().name());
                    ps.setString(4, operation.status().name());
                    ps.setString(5, operation.input());
                    ps.setString(6, operation.output());
                    ps.setString(7, toJson(operation.error()));
                    ps.setTimestamp(8, toTimestamp(operation.createdAt()));
                    ps.setTimestamp(9, toTimestamp(operation.completedAt()));
                });
    }

    @Override
    public List<OperationRecord> listOperations(int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, session_id, type, status, input, output, error, created_at, completed_at FROM helm_operation ORDER BY created_at ASC LIMIT ?",
                ps -> ps.setInt(1, effective),
                rs -> toOperation(rs));
    }

    @Override
    public List<OperationRecord> listOperations(Instant after, int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, session_id, type, status, input, output, error, created_at, completed_at FROM helm_operation WHERE created_at >= ? ORDER BY created_at ASC LIMIT ?",
                ps -> {
                    ps.setTimestamp(1, after == null ? toTimestamp(Instant.EPOCH) : toTimestamp(after));
                    ps.setInt(2, effective);
                },
                rs -> toOperation(rs));
    }

    private static OperationRecord toOperation(ResultSet rs) throws SQLException {
        HelmAction type = parseAction(rs.getString(3));
        if (type == null) {
            // Unknown/legacy type: skip defensively rather than poison the result set with a corrupted record.
            return null;
        }
        return new OperationRecord(
                rs.getString(1),
                rs.getString(2),
                type,
                OperationStatus.valueOf(rs.getString(4)),
                rs.getString(5),
                rs.getString(6),
                parseMap(rs.getString(7)),
                toInstant(rs.getTimestamp(8)),
                toInstant(rs.getTimestamp(9)));
    }

    // --- WorkflowRunStore ---

    @Override
    public Optional<WorkflowRunRecord> loadWorkflowRun(String id) {
        return queryOne(
                "SELECT workflow_name, status, input, output, error, created_at, completed_at FROM helm_workflow_run WHERE id = ?",
                ps -> ps.setString(1, id),
                rs -> new WorkflowRunRecord(
                        id,
                        rs.getString(1),
                        WorkflowRunStatus.valueOf(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        parseMap(rs.getString(5)),
                        toInstant(rs.getTimestamp(6)),
                        toInstant(rs.getTimestamp(7))));
    }

    @Override
    public void saveWorkflowRun(WorkflowRunRecord run) {
        execute(
                "MERGE INTO helm_workflow_run (id, workflow_name, status, input, output, error, created_at, completed_at) KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, run.id());
                    ps.setString(2, run.workflowName());
                    ps.setString(3, run.status().name());
                    ps.setString(4, run.input());
                    ps.setString(5, run.output());
                    ps.setString(6, toJson(run.error()));
                    ps.setTimestamp(7, toTimestamp(run.createdAt()));
                    ps.setTimestamp(8, toTimestamp(run.completedAt()));
                });
    }

    @Override
    public List<WorkflowRunRecord> listWorkflowRuns(int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, workflow_name, status, input, output, error, created_at, completed_at FROM helm_workflow_run ORDER BY created_at ASC LIMIT ?",
                ps -> ps.setInt(1, effective),
                rs -> new WorkflowRunRecord(
                        rs.getString(1),
                        rs.getString(2),
                        WorkflowRunStatus.valueOf(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        parseMap(rs.getString(6)),
                        toInstant(rs.getTimestamp(7)),
                        toInstant(rs.getTimestamp(8))));
    }

    // --- EventStore ---

    @Override
    public void appendEvent(RuntimeEventRecord event) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO helm_event (id, operation_id, workflow_run_id, sequence, type, payload, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, event.id());
                ps.setString(2, event.operationId());
                ps.setString(3, event.workflowRunId());
                ps.setLong(4, event.sequence());
                ps.setString(5, event.type().name());
                ps.setString(6, toJson(event.payload()));
                ps.setTimestamp(7, toTimestamp(event.createdAt()));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // Idempotent: a replayed event (same id, or same operation_id+sequence / workflow_run_id+sequence) already
            // exists. Treat as success for at-least-once retry safety — the caller's intent (the event is persisted)
            // is already satisfied.
            if (isUniqueViolation(e)) {
                return;
            }
            throw toHelmException(e);
        }
    }

    @Override
    public List<RuntimeEventRecord> eventsForOperation(String operationId, int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, workflow_run_id, sequence, type, payload, created_at FROM helm_event WHERE operation_id = ? ORDER BY sequence ASC LIMIT ?",
                ps -> {
                    ps.setString(1, operationId);
                    ps.setInt(2, effective);
                },
                rs -> toEvent(rs, operationId, null));
    }

    @Override
    public List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId, int limit) {
        int effective = cap(limit);
        return queryList(
                "SELECT id, operation_id, sequence, type, payload, created_at FROM helm_event WHERE workflow_run_id = ? ORDER BY sequence ASC LIMIT ?",
                ps -> {
                    ps.setString(1, workflowRunId);
                    ps.setInt(2, effective);
                },
                rs -> toEvent(rs, null, workflowRunId));
    }

    private static RuntimeEventRecord toEvent(ResultSet rs, String operationId, String workflowRunId)
            throws SQLException {
        RuntimeEventType type = parseEventType(rs.getString("type"));
        if (type == null) {
            return null;
        }
        return new RuntimeEventRecord(
                rs.getString("id"),
                operationId != null ? operationId : rs.getString("operation_id"),
                workflowRunId != null ? workflowRunId : rs.getString("workflow_run_id"),
                rs.getLong("sequence"),
                type,
                parseMap(rs.getString("payload")),
                toInstant(rs.getTimestamp("created_at")));
    }

    // --- JDBC plumbing ---

    private <T> Optional<T> queryOne(
            String sql, SqlConsumer<PreparedStatement> setter, SqlFunction<ResultSet, T> mapper) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                setter.accept(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    T value = rs.next() ? mapper.apply(rs) : null;
                    return Optional.ofNullable(value);
                }
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        }
    }

    private <T> List<T> queryList(String sql, SqlConsumer<PreparedStatement> setter, SqlFunction<ResultSet, T> mapper) {
        List<T> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                setter.accept(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        T value = mapper.apply(rs);
                        if (value != null) {
                            result.add(value);
                        }
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

    private static void rollbackQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // best-effort rollback
        }
    }

    private static int cap(int limit) {
        if (limit < 0) {
            return 0;
        }
        return Math.min(limit, DEFAULT_LIMIT);
    }

    private static boolean isUniqueViolation(SQLException e) {
        // SQLSTATE 23505 = unique constraint violation (H2 / SQL standard). Narrowed to 23505 specifically so that
        // a foreign-key violation (23503) or other integrity error (23xxx) is NOT swallowed as an idempotent replay.
        String sqlState = e.getSQLState();
        return "23505".equals(sqlState);
    }

    private static HelmAction parseAction(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return HelmAction.valueOf(name);
        } catch (IllegalArgumentException e) {
            // Unknown/legacy action value — skip defensively.
            return null;
        }
    }

    private static RuntimeEventType parseEventType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return RuntimeEventType.valueOf(name);
        } catch (IllegalArgumentException e) {
            // Unknown/legacy event type — skip defensively.
            return null;
        }
    }

    // --- (de)serialization ---

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.addMixIn(ContentBlock.class, ContentBlockMixin.class);
        return mapper;
    }

    private static List<HelmMessage> parseMessages(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new PersistenceException(
                    "failed to parse session messages", Map.of(), Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new PersistenceException(
                    "failed to parse map", Map.of(), Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new PersistenceException(
                    "failed to serialize payload", Map.of(), Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Maps a {@link SQLException} to a {@link PersistenceException}, preserving the original exception as the cause and
     * the {@link SQLException#getNextException() next-exception} chain in {@code developerDetails}. Adds a
     * {@code retryable} flag to {@code details} derived from the SQLSTATE class:
     *
     * <ul>
     *   <li>{@code 40xxx} (transaction rollback / serialization) → {@code retryable=true}
     *   <li>{@code 23xxx} (integrity-constraint violation) → {@code retryable=false}
     *   <li>everything else → {@code retryable=false}
     * </ul>
     *
     * The {@code sqlState} key is omitted from {@code details} when the driver did not supply one (no {@code "null"}
     * string).
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ToolCallBlock.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    })
    private interface ContentBlockMixin {}

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
