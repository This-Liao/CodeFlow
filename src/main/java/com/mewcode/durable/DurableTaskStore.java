package com.mewcode.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crash-safe durable task store. Snapshots are atomically replaced while an
 * append-only JSONL event log preserves the transition audit trail.
 */
public final class DurableTaskStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RNG = new SecureRandom();

    private final Path tasksDir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public DurableTaskStore(String workDir) {
        this.tasksDir = Path.of(workDir, ".codeflow", "tasks").toAbsolutePath().normalize();
    }

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
        synchronized (lock(task.getId())) {
            writeSnapshot(task);
            appendEvent(task, "created", Map.of("state", task.getState().name()));
        }
        return task;
    }

    public Optional<DurableTask> get(String id) {
        validateId(id);
        Path snapshot = taskDir(id).resolve("task.json");
        if (!Files.isRegularFile(snapshot)) return Optional.empty();
        synchronized (lock(id)) {
            try {
                return Optional.of(MAPPER.readValue(snapshot.toFile(), DurableTask.class));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read durable task " + id, e);
            }
        }
    }

    public List<DurableTask> list(boolean recoverableOnly) {
        if (!Files.isDirectory(tasksDir)) return List.of();
        var tasks = new ArrayList<DurableTask>();
        try (var dirs = Files.list(tasksDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                get(dir.getFileName().toString()).ifPresent(task -> {
                    if (!recoverableOnly || task.getState().isRecoverable()) tasks.add(task);
                });
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list durable tasks", e);
        }
        tasks.sort(Comparator.comparingLong(DurableTask::getUpdatedAt).reversed());
        return List.copyOf(tasks);
    }

    public DurableTask transition(String id, DurableTaskState next, String reason, Long expectedVersion) {
        validateId(id);
        synchronized (lock(id)) {
            DurableTask task = get(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
            if (expectedVersion != null && expectedVersion != task.getVersion()) {
                throw new IllegalStateException("version conflict: expected " + expectedVersion
                        + " but found " + task.getVersion());
            }
            DurableTaskState previous = task.getState();
            if (!previous.canTransitionTo(next)) {
                throw new IllegalStateException("invalid transition: " + previous + " -> " + next);
            }
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
            task.setUpdatedAt(System.currentTimeMillis());
            task.setVersion(task.getVersion() + 1);
            writeSnapshot(task);
            var detail = new LinkedHashMap<String, Object>();
            detail.put("from", previous.name());
            detail.put("to", next.name());
            if (reason != null && !reason.isBlank()) detail.put("reason", reason);
            appendEvent(task, "transition", detail);
            return task;
        }
    }

    public DurableTask resume(String id, String reason) {
        DurableTask current = get(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
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

    public DurableTask checkpoint(String id, Map<String, Object> checkpoint, String reason) {
        validateId(id);
        synchronized (lock(id)) {
            DurableTask task = get(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
            if (task.getState().isTerminal()) throw new IllegalStateException("terminal task cannot be checkpointed");
            task.setCheckpoint(checkpoint);
            task.setUpdatedAt(System.currentTimeMillis());
            task.setVersion(task.getVersion() + 1);
            writeSnapshot(task);
            appendEvent(task, "checkpoint", reason == null ? Map.of() : Map.of("reason", reason));
            return task;
        }
    }

    public DurableTask addArtifact(String id, String name, String uri, String mediaType,
                                   Map<String, Object> metadata) {
        validateId(id);
        synchronized (lock(id)) {
            DurableTask task = get(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
            var artifact = new LinkedHashMap<String, Object>();
            artifact.put("id", newId());
            artifact.put("name", name);
            artifact.put("uri", uri);
            artifact.put("mediaType", mediaType == null ? "application/octet-stream" : mediaType);
            artifact.put("metadata", metadata == null ? Map.of() : metadata);
            task.getArtifacts().add(artifact);
            task.setUpdatedAt(System.currentTimeMillis());
            task.setVersion(task.getVersion() + 1);
            writeSnapshot(task);
            appendEvent(task, "artifact", artifact);
            return task;
        }
    }

    public Path eventLog(String id) {
        validateId(id);
        return taskDir(id).resolve("events.jsonl");
    }

    private void writeSnapshot(DurableTask task) {
        Path dir = taskDir(task.getId());
        Path target = dir.resolve("task.json");
        Path temp = dir.resolve("task.json.tmp");
        try {
            Files.createDirectories(dir);
            MAPPER.writeValue(temp.toFile(), task);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist durable task " + task.getId(), e);
        }
    }

    private void appendEvent(DurableTask task, String type, Map<String, Object> detail) {
        var event = new LinkedHashMap<String, Object>();
        event.put("timestamp", Instant.now().toString());
        event.put("taskId", task.getId());
        event.put("version", task.getVersion());
        event.put("type", type);
        event.put("detail", detail);
        try {
            String line = MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(event) + System.lineSeparator();
            Files.writeString(eventLog(task.getId()), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot append durable task event", e);
        }
    }

    private Object lock(String id) {
        return locks.computeIfAbsent(id, ignored -> new Object());
    }

    private Path taskDir(String id) {
        Path result = tasksDir.resolve(id).normalize();
        if (!result.startsWith(tasksDir)) throw new IllegalArgumentException("invalid task id");
        return result;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{3,80}")) {
            throw new IllegalArgumentException("invalid task id");
        }
    }

    private static String newId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        var out = new StringBuilder("cf-");
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
