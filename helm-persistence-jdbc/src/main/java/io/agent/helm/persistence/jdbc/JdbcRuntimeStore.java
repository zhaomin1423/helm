package io.agent.helm.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.message.ContentBlock;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.TextBlock;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC {@link RuntimeStore}. Payloads (messages, inputs, outputs, errors, event payloads) are stored as JSON text
 * columns; timestamps as TIMESTAMP. Each write is its own transaction. SQL errors are mapped to
 * {@link PersistenceException}.
 */
public final class JdbcRuntimeStore implements RuntimeStore {
    private static final ObjectMapper MAPPER = createMapper();

    private final DataSource dataSource;

    public JdbcRuntimeStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

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

    @Override
    public void saveSession(AgentSessionState session) {
        execute(
                "MERGE INTO helm_session (id, agent_name, instance_id, session_name, version, messages, created_at, updated_at) KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, session.id());
                    ps.setString(2, session.agentName());
                    ps.setString(3, session.instanceId());
                    ps.setString(4, session.sessionName());
                    ps.setLong(5, session.version());
                    ps.setString(6, toJson(session.messages()));
                    ps.setTimestamp(7, toTimestamp(session.createdAt()));
                    ps.setTimestamp(8, toTimestamp(session.updatedAt()));
                });
    }

    @Override
    public List<AgentSessionState> listSessions() {
        return queryList(
                "SELECT id, agent_name, instance_id, session_name, version, messages, created_at, updated_at FROM helm_session ORDER BY created_at ASC",
                ps -> {},
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
        execute("DELETE FROM helm_session WHERE id = ?", ps -> ps.setString(1, sessionId));
    }

    @Override
    public Optional<OperationRecord> loadOperation(String id) {
        return queryOne(
                "SELECT session_id, type, status, input, output, error, created_at, completed_at FROM helm_operation WHERE id = ?",
                ps -> ps.setString(1, id),
                rs -> new OperationRecord(
                        id,
                        rs.getString(1),
                        rs.getString(2),
                        OperationStatus.valueOf(rs.getString(3)),
                        parseObject(rs.getString(4)),
                        parseObject(rs.getString(5)),
                        parseMap(rs.getString(6)),
                        toInstant(rs.getTimestamp(7)),
                        toInstant(rs.getTimestamp(8))));
    }

    @Override
    public void saveOperation(OperationRecord operation) {
        execute(
                "MERGE INTO helm_operation (id, session_id, type, status, input, output, error, created_at, completed_at) KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, operation.id());
                    ps.setString(2, operation.sessionId());
                    ps.setString(3, operation.type());
                    ps.setString(4, operation.status().name());
                    ps.setString(5, operation.input() == null ? null : toJson(operation.input()));
                    ps.setString(6, operation.output() == null ? null : toJson(operation.output()));
                    ps.setString(7, toJson(operation.error()));
                    ps.setTimestamp(8, toTimestamp(operation.createdAt()));
                    ps.setTimestamp(9, toTimestamp(operation.completedAt()));
                });
    }

    @Override
    public List<OperationRecord> listOperations() {
        return queryList(
                "SELECT id, session_id, type, status, input, output, error, created_at, completed_at FROM helm_operation ORDER BY created_at ASC",
                ps -> {},
                rs -> new OperationRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        OperationStatus.valueOf(rs.getString(4)),
                        parseObject(rs.getString(5)),
                        parseObject(rs.getString(6)),
                        parseMap(rs.getString(7)),
                        toInstant(rs.getTimestamp(8)),
                        toInstant(rs.getTimestamp(9))));
    }

    @Override
    public Optional<WorkflowRunRecord> loadWorkflowRun(String id) {
        return queryOne(
                "SELECT workflow_name, status, input, output, error, created_at, completed_at FROM helm_workflow_run WHERE id = ?",
                ps -> ps.setString(1, id),
                rs -> new WorkflowRunRecord(
                        id,
                        rs.getString(1),
                        WorkflowRunStatus.valueOf(rs.getString(2)),
                        parseObject(rs.getString(3)),
                        parseObject(rs.getString(4)),
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
                    ps.setString(4, run.input() == null ? null : toJson(run.input()));
                    ps.setString(5, run.output() == null ? null : toJson(run.output()));
                    ps.setString(6, toJson(run.error()));
                    ps.setTimestamp(7, toTimestamp(run.createdAt()));
                    ps.setTimestamp(8, toTimestamp(run.completedAt()));
                });
    }

    @Override
    public List<WorkflowRunRecord> listWorkflowRuns() {
        return queryList(
                "SELECT id, workflow_name, status, input, output, error, created_at, completed_at FROM helm_workflow_run ORDER BY created_at ASC",
                ps -> {},
                rs -> new WorkflowRunRecord(
                        rs.getString(1),
                        rs.getString(2),
                        WorkflowRunStatus.valueOf(rs.getString(3)),
                        parseObject(rs.getString(4)),
                        parseObject(rs.getString(5)),
                        parseMap(rs.getString(6)),
                        toInstant(rs.getTimestamp(7)),
                        toInstant(rs.getTimestamp(8))));
    }

    @Override
    public void appendEvent(RuntimeEventRecord event) {
        execute(
                "INSERT INTO helm_event (id, operation_id, workflow_run_id, sequence, type, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, event.id());
                    ps.setString(2, event.operationId());
                    ps.setString(3, event.workflowRunId());
                    ps.setLong(4, event.sequence());
                    ps.setString(5, event.type());
                    ps.setString(6, toJson(event.payload()));
                    ps.setTimestamp(7, toTimestamp(event.createdAt()));
                });
    }

    @Override
    public List<RuntimeEventRecord> eventsForOperation(String operationId) {
        return queryList(
                "SELECT id, workflow_run_id, sequence, type, payload, created_at FROM helm_event WHERE operation_id = ? ORDER BY sequence ASC",
                ps -> ps.setString(1, operationId),
                rs -> new RuntimeEventRecord(
                        rs.getString(1),
                        operationId,
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getString(4),
                        parseMap(rs.getString(5)),
                        toInstant(rs.getTimestamp(6))));
    }

    @Override
    public List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId) {
        return queryList(
                "SELECT id, operation_id, sequence, type, payload, created_at FROM helm_event WHERE workflow_run_id = ? ORDER BY sequence ASC",
                ps -> ps.setString(1, workflowRunId),
                rs -> new RuntimeEventRecord(
                        rs.getString(1),
                        rs.getString(2),
                        workflowRunId,
                        rs.getLong(3),
                        rs.getString(4),
                        parseMap(rs.getString(5)),
                        toInstant(rs.getTimestamp(6))));
    }

    private <T> Optional<T> queryOne(
            String sql, SqlConsumer<PreparedStatement> setter, SqlFunction<ResultSet, T> mapper) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            setter.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return Optional.ofNullable(rs.next() ? mapper.apply(rs) : null);
            }
        } catch (SQLException e) {
            throw toHelmException(e);
        }
    }

    private <T> List<T> queryList(String sql, SqlConsumer<PreparedStatement> setter, SqlFunction<ResultSet, T> mapper) {
        List<T> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            setter.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.apply(rs));
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

    private static Object parseObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new PersistenceException(
                    "failed to parse payload", Map.of(), Map.of("message", String.valueOf(e.getMessage())));
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

    private static PersistenceException toHelmException(SQLException e) {
        return new PersistenceException(
                "persistence error",
                Map.of("sqlState", String.valueOf(e.getSQLState())),
                Map.of("message", String.valueOf(e.getMessage())));
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
