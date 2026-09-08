package com.codeflow.durable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * PostgreSQL durable-task backend for multiple competing workers.
 *
 * <p>Task mutations use row locks plus optimistic versions. Worker acquisition
 * uses {@code FOR UPDATE SKIP LOCKED}; every acquisition increments a fencing
 * token so a paused/partitioned worker cannot renew or release a newer lease.</p>
 */
public final class PostgresDurableTaskRepository implements DistributedDurableTaskRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();

    private final String jdbcUrl;
    private final Properties connectionProperties;
    private final String tasksTable;
    private final String eventsTable;
    private final Duration defaultLease;
    private final ThreadLocal<LeaseGuard> activeGuard = new ThreadLocal<>();

    public PostgresDurableTaskRepository(String jdbcUrl, String username, String password,
                                         String schema, int leaseSeconds) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL must start with jdbc:postgresql:");
        }
        String safeSchema = schema == null || schema.isBlank() ? "codeflow" : schema;
        if (!safeSchema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("invalid PostgreSQL schema");
        }
        this.jdbcUrl = jdbcUrl;
        this.connectionProperties = new Properties();
        if (username != null && !username.isBlank()) connectionProperties.setProperty("user", username);
        if (password != null && !password.isBlank()) connectionProperties.setProperty("password", password);
        String quotedSchema = '"' + safeSchema + '"';
        this.tasksTable = quotedSchema + ".durable_tasks";
        this.eventsTable = quotedSchema + ".durable_task_events";
        this.defaultLease = Duration.ofSeconds(Math.max(5, leaseSeconds));
        initializeSchema(quotedSchema);
    }

    @Override
    public DurableTask create(String prompt, String sessionId, String owner, int maxAttempts) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        DurableTask task = new DurableTask();
        long now = System.currentTimeMillis();
        task.setId(newId());
        task.setPrompt(prompt);
        task.setSessionId(sessionId);
        task.setOwner(owner);
        task.setMaxAttempts(Math.max(1, maxAttempts));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setVersion(1);

        return transaction(connection -> {
            String sql = "INSERT INTO " + tasksTable + " "
                    + "(id, state, version, updated_at, payload) VALUES (?, ?, ?, ?, ?::jsonb)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, task.getId());
                statement.setString(2, task.getState().name());
                statement.setLong(3, task.getVersion());
                statement.setLong(4, task.getUpdatedAt());
                statement.setString(5, json(task));
                statement.executeUpdate();
            }
            appendEvent(connection, task, "created", Map.of("state", task.getState().name()));
            return task;
        });
    }

    @Override
    public Optional<DurableTask> get(String id) {
        validateId(id);
        String sql = "SELECT payload FROM " + tasksTable + " WHERE id = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readTask(results)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseFailure("read durable task", e);
        }
    }

    @Override
    public List<DurableTask> list(boolean recoverableOnly) {
        String sql = "SELECT payload FROM " + tasksTable
                + (recoverableOnly ? " WHERE state NOT IN ('COMPLETED','FAILED','CANCELED')" : "")
                + " ORDER BY updated_at DESC";
        var tasks = new ArrayList<DurableTask>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) tasks.add(readTask(results));
        } catch (SQLException e) {
            throw databaseFailure("list durable tasks", e);
        }
        tasks.sort(Comparator.comparingLong(DurableTask::getUpdatedAt).reversed());
        return List.copyOf(tasks);
    }

    @Override
    public DurableTask transition(String id, DurableTaskState requested, String reason, Long expectedVersion) {
        validateId(id);
        return mutate(id, task -> {
            if (expectedVersion != null && expectedVersion != task.getVersion()) {
                throw new IllegalStateException("version conflict: expected " + expectedVersion
                        + " but found " + task.getVersion());
            }
            DurableTaskState previous = task.getState();
            if (!previous.canTransitionTo(requested)) {
                throw new IllegalStateException("invalid transition: " + previous + " -> " + requested);
            }
            DurableTaskState next = requested;
            if (next == DurableTaskState.PAUSED || next == DurableTaskState.WAITING_APPROVAL) {
                task.setResumeState(previous);
            }
            if (previous == DurableTaskState.FAILED_RETRYABLE && next == DurableTaskState.EXECUTING) {
                task.setAttempt(task.getAttempt() + 1);
            }
            if (next == DurableTaskState.FAILED_RETRYABLE) {
                task.setLastError(reason);
                if (task.getAttempt() + 1 >= task.getMaxAttempts()) next = DurableTaskState.FAILED;
            }
            task.setState(next);
            var detail = new LinkedHashMap<String, Object>();
            detail.put("from", previous.name());
            detail.put("to", next.name());
            if (reason != null && !reason.isBlank()) detail.put("reason", reason);
            return new Mutation("transition", detail);
        });
    }

    @Override
    public DurableTask resume(String id, String reason) {
        DurableTask current = get(id)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
        if (current.getState() != DurableTaskState.PAUSED
                && current.getState() != DurableTaskState.WAITING_APPROVAL
                && current.getState() != DurableTaskState.FAILED_RETRYABLE) {
            throw new IllegalStateException("task is not resumable from " + current.getState());
        }
        DurableTaskState target = current.getState() == DurableTaskState.FAILED_RETRYABLE
                ? DurableTaskState.EXECUTING
                : Optional.ofNullable(current.getResumeState()).orElse(DurableTaskState.EXECUTING);
        return transition(id, target, reason, current.getVersion());
    }

    @Override
    public DurableTask recover(String id, String owner, String reason, Long expectedVersion) {
        validateId(id);
        return mutate(id, task -> {
            if (task.getState().isTerminal()) {
                throw new IllegalStateException("terminal task cannot be recovered");
            }
            if (expectedVersion != null && expectedVersion != task.getVersion()) {
                throw new IllegalStateException("version conflict: expected " + expectedVersion
                        + " but found " + task.getVersion());
            }
            String previousOwner = task.getOwner();
            task.setOwner(owner);
            var detail = new LinkedHashMap<String, Object>();
            detail.put("state", task.getState().name());
            if (previousOwner != null && !previousOwner.isBlank()) detail.put("previousOwner", previousOwner);
            if (owner != null && !owner.isBlank()) detail.put("owner", owner);
            if (reason != null && !reason.isBlank()) detail.put("reason", reason);
            return new Mutation("recovered", detail);
        });
    }

    @Override
    public DurableTask checkpoint(String id, Map<String, Object> checkpoint, String reason) {
        validateId(id);
        return mutate(id, task -> {
            if (task.getState().isTerminal()) {
                throw new IllegalStateException("terminal task cannot be checkpointed");
            }
            task.setCheckpoint(checkpoint);
            return new Mutation("checkpoint", reason == null ? Map.of() : Map.of("reason", reason));
        });
    }

    @Override
    public DurableTask addArtifact(String id, String name, String uri, String mediaType,
                                   Map<String, Object> metadata) {
        validateId(id);
        return mutate(id, task -> {
            var artifact = new LinkedHashMap<String, Object>();
            artifact.put("id", newId());
            artifact.put("name", name);
            artifact.put("uri", uri);
            artifact.put("mediaType", mediaType == null ? "application/octet-stream" : mediaType);
            artifact.put("metadata", metadata == null ? Map.of() : metadata);
            task.getArtifacts().add(artifact);
            return new Mutation("artifact", artifact);
        });
    }

    @Override
    public Optional<Lease> claim(String taskId, String workerId, Duration leaseDuration) {
        validateId(taskId);
        validateWorker(workerId);
        long seconds = leaseSeconds(leaseDuration);
        return transaction(connection -> {
            String sql = "UPDATE " + tasksTable + " task SET lease_owner = ?,"
                    + " lease_expires_at = now() + (? * interval '1 second'),"
                    + " fencing_token = task.fencing_token + 1"
                    + " WHERE task.id = ? AND task.state NOT IN ('COMPLETED','FAILED','CANCELED')"
                    + " AND (task.lease_expires_at IS NULL OR task.lease_expires_at < now()"
                    + " OR task.lease_owner = ?)"
                    + " RETURNING task.id, task.fencing_token, task.lease_expires_at, task.payload";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, workerId);
                statement.setLong(2, seconds);
                statement.setString(3, taskId);
                statement.setString(4, workerId);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) return Optional.empty();
                    DurableTask task = readTask(results);
                    Lease lease = leaseFrom(results, workerId, task);
                    appendLeaseEvent(connection, task, lease);
                    return Optional.of(lease);
                }
            }
        });
    }

    @Override
    public Optional<Lease> claimNext(String workerId, Duration leaseDuration) {
        validateWorker(workerId);
        long seconds = leaseSeconds(leaseDuration);
        return transaction(connection -> {
            String sql = "WITH candidate AS (SELECT id FROM " + tasksTable
                    + " WHERE state NOT IN ('COMPLETED','FAILED','CANCELED')"
                    + " AND (lease_expires_at IS NULL OR lease_expires_at < now())"
                    + " ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT 1)"
                    + " UPDATE " + tasksTable + " task SET lease_owner = ?,"
                    + " lease_expires_at = now() + (? * interval '1 second'),"
                    + " fencing_token = task.fencing_token + 1 FROM candidate"
                    + " WHERE task.id = candidate.id"
                    + " RETURNING task.id, task.fencing_token, task.lease_expires_at, task.payload";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, workerId);
                statement.setLong(2, seconds);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) return Optional.empty();
                    DurableTask task = readTask(results);
                    Lease lease = leaseFrom(results, workerId, task);
                    appendLeaseEvent(connection, task, lease);
                    return Optional.of(lease);
                }
            }
        });
    }

    @Override
    public boolean heartbeat(String taskId, String workerId, long fencingToken, Duration leaseDuration) {
        validateId(taskId);
        validateWorker(workerId);
        String sql = "UPDATE " + tasksTable
                + " SET lease_expires_at = now() + (? * interval '1 second')"
                + " WHERE id = ? AND lease_owner = ? AND fencing_token = ?"
                + " AND lease_expires_at >= now()"
                + " AND state NOT IN ('COMPLETED','FAILED','CANCELED')";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, leaseSeconds(leaseDuration));
            statement.setString(2, taskId);
            statement.setString(3, workerId);
            statement.setLong(4, fencingToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw databaseFailure("heartbeat durable task lease", e);
        }
    }

    @Override
    public boolean release(String taskId, String workerId, long fencingToken) {
        validateId(taskId);
        validateWorker(workerId);
        return transaction(connection -> {
            String sql = "UPDATE " + tasksTable
                    + " SET lease_owner = NULL, lease_expires_at = NULL"
                    + " WHERE id = ? AND lease_owner = ? AND fencing_token = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, taskId);
                statement.setString(2, workerId);
                statement.setLong(3, fencingToken);
                boolean released = statement.executeUpdate() == 1;
                if (released) {
                    DurableTask task = selectForUpdate(connection, taskId);
                    appendEvent(connection, task, "lease_released", Map.of(
                            "workerId", workerId, "fencingToken", fencingToken));
                }
                return released;
            }
        });
    }

    @Override
    public DurableTaskRepository fenced(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("lease is required");
        return new FencedRepository(new LeaseGuard(
                lease.taskId(), lease.workerId(), lease.fencingToken()));
    }

    private DurableTask mutate(String id, TaskMutation mutation) {
        return transaction(connection -> {
            DurableTask task = selectForUpdate(connection, id);
            long previousVersion = task.getVersion();
            Mutation event = mutation.apply(task);
            task.setUpdatedAt(System.currentTimeMillis());
            task.setVersion(previousVersion + 1);
            LeaseGuard guard = activeGuard.get();
            String sql = "UPDATE " + tasksTable
                    + " SET state = ?, version = ?, updated_at = ?, payload = ?::jsonb"
                    + " WHERE id = ? AND version = ?"
                    + (guard == null ? "" : " AND lease_owner = ? AND fencing_token = ?"
                    + " AND lease_expires_at >= now()");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, task.getState().name());
                statement.setLong(2, task.getVersion());
                statement.setLong(3, task.getUpdatedAt());
                statement.setString(4, json(task));
                statement.setString(5, task.getId());
                statement.setLong(6, previousVersion);
                if (guard != null) {
                    if (!guard.taskId().equals(task.getId())) {
                        throw new IllegalStateException("fenced repository cannot mutate another task");
                    }
                    statement.setString(7, guard.workerId());
                    statement.setLong(8, guard.fencingToken());
                }
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException(guard == null ? "version conflict" : "worker lease lost");
                }
            }
            appendEvent(connection, task, event.type(), event.detail());
            return task;
        });
    }

    private DurableTask selectForUpdate(Connection connection, String id) throws SQLException {
        String sql = "SELECT payload FROM " + tasksTable + " WHERE id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new IllegalArgumentException("task not found: " + id);
                return readTask(results);
            }
        }
    }

    private void appendEvent(Connection connection, DurableTask task, String type,
                             Map<String, Object> detail) throws SQLException {
        String sql = "INSERT INTO " + eventsTable
                + " (task_id, version, event_type, detail) VALUES (?, ?, ?, ?::jsonb)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.getId());
            statement.setLong(2, task.getVersion());
            statement.setString(3, type);
            statement.setString(4, json(detail));
            statement.executeUpdate();
        }
    }

    private static Lease leaseFrom(ResultSet results, String workerId, DurableTask task)
            throws SQLException {
        return new Lease(results.getString("id"), workerId,
                results.getLong("fencing_token"),
                results.getTimestamp("lease_expires_at").toInstant(), task);
    }

    private void appendLeaseEvent(Connection connection, DurableTask task, Lease lease)
            throws SQLException {
        appendEvent(connection, task, "lease_acquired", Map.of(
                "workerId", lease.workerId(),
                "fencingToken", lease.fencingToken(),
                "expiresAt", lease.expiresAt().toString()));
    }

    private void initializeSchema(String quotedSchema) {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema);
            statement.execute("CREATE TABLE IF NOT EXISTS " + tasksTable + " ("
                    + "id varchar(80) PRIMARY KEY, state varchar(40) NOT NULL,"
                    + "version bigint NOT NULL, updated_at bigint NOT NULL, payload jsonb NOT NULL,"
                    + "lease_owner varchar(160), lease_expires_at timestamptz,"
                    + "fencing_token bigint NOT NULL DEFAULT 0)");
            statement.execute("CREATE INDEX IF NOT EXISTS durable_tasks_claim_idx ON " + tasksTable
                    + " (state, lease_expires_at, updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + eventsTable + " ("
                    + "sequence bigserial PRIMARY KEY, task_id varchar(80) NOT NULL REFERENCES "
                    + tasksTable + "(id) ON DELETE CASCADE, event_at timestamptz NOT NULL DEFAULT now(),"
                    + "version bigint NOT NULL, event_type varchar(80) NOT NULL, detail jsonb NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS durable_task_events_task_idx ON "
                    + eventsTable + " (task_id, sequence)");
        } catch (SQLException e) {
            throw databaseFailure("initialize PostgreSQL durable schema", e);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof SQLException sql) throw databaseFailure("mutate durable task", sql);
                throw new IllegalStateException(failure);
            }
        } catch (SQLException e) {
            throw databaseFailure("open PostgreSQL transaction", e);
        }
    }

    private static DurableTask readTask(ResultSet results) throws SQLException {
        try {
            return MAPPER.readValue(results.getString("payload"), DurableTask.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("invalid durable task JSON", e);
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize durable task", e);
        }
    }

    private long leaseSeconds(Duration requested) {
        Duration lease = requested == null ? defaultLease : requested;
        return Math.max(1, lease.toSeconds());
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{3,80}")) {
            throw new IllegalArgumentException("invalid task id");
        }
    }

    private static void validateWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 160) {
            throw new IllegalArgumentException("invalid worker id");
        }
    }

    private static String newId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        var out = new StringBuilder("cf-");
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static IllegalStateException databaseFailure(String operation, SQLException failure) {
        return new IllegalStateException("Cannot " + operation + ": " + failure.getMessage(), failure);
    }

    private <T> T withGuard(LeaseGuard guard, Supplier<T> operation) {
        LeaseGuard previous = activeGuard.get();
        activeGuard.set(guard);
        try {
            return operation.get();
        } finally {
            if (previous == null) activeGuard.remove(); else activeGuard.set(previous);
        }
    }

    private final class FencedRepository implements DurableTaskRepository {
        private final LeaseGuard guard;

        private FencedRepository(LeaseGuard guard) { this.guard = guard; }
        @Override public DurableTask create(String prompt, String sessionId, String owner, int maxAttempts) {
            return PostgresDurableTaskRepository.this.create(prompt, sessionId, owner, maxAttempts);
        }
        @Override public Optional<DurableTask> get(String id) {
            return PostgresDurableTaskRepository.this.get(id);
        }
        @Override public List<DurableTask> list(boolean recoverableOnly) {
            return PostgresDurableTaskRepository.this.list(recoverableOnly);
        }
        @Override public DurableTask transition(String id, DurableTaskState next, String reason,
                                                Long expectedVersion) {
            return withGuard(guard, () -> PostgresDurableTaskRepository.this
                    .transition(id, next, reason, expectedVersion));
        }
        @Override public DurableTask resume(String id, String reason) {
            return withGuard(guard, () -> PostgresDurableTaskRepository.this.resume(id, reason));
        }
        @Override public DurableTask recover(String id, String owner, String reason,
                                             Long expectedVersion) {
            return withGuard(guard, () -> PostgresDurableTaskRepository.this
                    .recover(id, owner, reason, expectedVersion));
        }
        @Override public DurableTask checkpoint(String id, Map<String, Object> checkpoint, String reason) {
            return withGuard(guard, () -> PostgresDurableTaskRepository.this
                    .checkpoint(id, checkpoint, reason));
        }
        @Override public DurableTask addArtifact(String id, String name, String uri, String mediaType,
                                                 Map<String, Object> metadata) {
            return withGuard(guard, () -> PostgresDurableTaskRepository.this
                    .addArtifact(id, name, uri, mediaType, metadata));
        }
    }

    private record Mutation(String type, Map<String, Object> detail) { }
    private record LeaseGuard(String taskId, String workerId, long fencingToken) { }
    @FunctionalInterface private interface TaskMutation { Mutation apply(DurableTask task); }
    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws Exception; }
}
