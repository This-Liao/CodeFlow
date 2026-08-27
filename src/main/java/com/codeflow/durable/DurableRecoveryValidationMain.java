package com.codeflow.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeflow.tool.impl.EditFileTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-process validation worker used by scripts/record_e2e_demo.py.
 *
 * <p>The prepare process executes a real EditFile tool, atomically persists a
 * checkpoint, then waits to be killed. A new JVM loads that checkpoint,
 * records ownership recovery, skips the completed tool call, verifies the
 * file, and completes the task.
 */
public final class DurableRecoveryValidationMain {
    public static final String READY_PREFIX = "CODEFLOW_CRASH_READY=";
    public static final String RESULT_PREFIX = "CODEFLOW_DURABLE_RECOVERY_JSON=";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STEP = "edit-recovery-fixture";
    private static final String BEFORE = "return \"before\";";
    private static final String AFTER = "return \"after\";";

    private DurableRecoveryValidationMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <prepare|resume> <work-dir> [task-id]");
        }
        Path workDir = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        switch (args[0]) {
            case "prepare" -> prepare(workDir);
            case "resume" -> {
                if (args.length < 3) throw new IllegalArgumentException("resume requires task-id");
                resume(workDir, args[2]);
            }
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void prepare(Path workDir) throws Exception {
        Path fixture = fixture(workDir);
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, """
                package fixture;

                public final class RecoveryFixture {
                    public static String state() {
                        return "before";
                    }
                }
                """, StandardCharsets.UTF_8);

        DurableTaskStore store = new DurableTaskStore(workDir.toString());
        DurableTask task = store.create(
                "Edit the fixture, survive a forced process crash, resume, and verify.",
                "crash-recovery-validation", processOwner(), 3);
        task = store.transition(task.getId(), DurableTaskState.PLANNING,
                "deterministic plan persisted", task.getVersion());
        task = store.transition(task.getId(), DurableTaskState.EXECUTING,
                "executing edit step", task.getVersion());

        executeEditTool(workDir, fixture);
        String hash = sha256(fixture);
        var checkpoint = new LinkedHashMap<String, Object>();
        checkpoint.put("completedSteps", List.of(STEP));
        checkpoint.put("nextStep", "verify");
        checkpoint.put("lastTool", "EditFile");
        checkpoint.put("lastToolId", STEP);
        checkpoint.put("completedToolCalls", 1);
        checkpoint.put("fileSha256", hash);
        task = store.checkpoint(task.getId(), checkpoint, "safe boundary after EditFile");

        ObjectNode ready = MAPPER.createObjectNode();
        ready.put("taskId", task.getId());
        ready.put("state", task.getState().name());
        ready.put("checkpointVersion", task.getVersion());
        ready.put("completedToolCalls", 1);
        ready.put("fileSha256", hash);
        System.out.println(READY_PREFIX + MAPPER.writeValueAsString(ready));
        System.out.flush();

        // The recorder forcibly terminates this JVM after observing READY_PREFIX.
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void resume(Path workDir, String taskId) throws Exception {
        long started = System.nanoTime();
        DurableTaskStore store = new DurableTaskStore(workDir.toString());
        DurableTask beforeRecovery = store.get(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        DurableTaskState recoveredFrom = beforeRecovery.getState();
        DurableTask task = store.recover(
                taskId, processOwner(), "new JVM resumed from persisted checkpoint",
                beforeRecovery.getVersion());

        List<?> completed = task.getCheckpoint().get("completedSteps") instanceof List<?> list
                ? list : List.of();
        boolean reusedCheckpoint = completed.contains(STEP);
        if (!reusedCheckpoint) {
            executeEditTool(workDir, fixture(workDir));
            var repaired = new LinkedHashMap<>(task.getCheckpoint());
            repaired.put("completedSteps", List.of(STEP));
            task = store.checkpoint(taskId, repaired, "reconstructed missing checkpoint");
        }

        task = store.transition(taskId, DurableTaskState.VERIFYING,
                "resumed at nextStep=verify", task.getVersion());
        Path fixture = fixture(workDir);
        String content = Files.readString(fixture, StandardCharsets.UTF_8);
        String actualHash = sha256(fixture);
        String checkpointHash = String.valueOf(task.getCheckpoint().get("fileSha256"));
        long editCalls = auditCount(workDir);
        boolean fileVerified = content.contains(AFTER) && !content.contains(BEFORE);
        boolean hashPreserved = actualHash.equals(checkpointHash);
        if (!fileVerified || !hashPreserved || editCalls != 1) {
            throw new IllegalStateException(
                    "recovery verification failed: fileVerified=" + fileVerified
                            + ", hashPreserved=" + hashPreserved + ", editCalls=" + editCalls);
        }

        task = store.addArtifact(taskId, "crash-recovery-fixture",
                fixture.toUri().toString(), "text/x-java-source",
                Map.of("sha256", actualHash, "verified", true));
        task = store.transition(taskId, DurableTaskState.COMPLETED,
                "checkpoint resumed without duplicate tool execution", task.getVersion());

        long recoveryMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        long eventCount;
        try (var lines = Files.lines(store.eventLog(taskId))) {
            eventCount = lines.count();
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("result", "PASS");
        result.put("taskId", taskId);
        result.put("recoveredFrom", recoveredFrom.name());
        result.put("checkpointStep", STEP);
        result.put("reusedCheckpoint", reusedCheckpoint);
        result.put("editToolCalls", editCalls);
        result.put("duplicateToolCalls", Math.max(0, editCalls - 1));
        result.put("fileHashPreserved", hashPreserved);
        result.put("eventCount", eventCount);
        result.put("artifacts", task.getArtifacts().size());
        result.put("finalState", task.getState().name());
        result.put("recoveryDurationMs", recoveryMs);
        System.out.println(RESULT_PREFIX + MAPPER.writeValueAsString(result));
    }

    private static void executeEditTool(Path workDir, Path fixture) throws Exception {
        appendAudit(workDir, STEP, "EditFile");
        var result = new EditFileTool().execute(Map.of(
                "file_path", fixture.toString(),
                "old_string", BEFORE,
                "new_string", AFTER));
        if (result.isError()) throw new IllegalStateException(result.output());
    }

    private static Path fixture(Path workDir) {
        return workDir.resolve("fixture").resolve("RecoveryFixture.java");
    }

    private static Path audit(Path workDir) {
        return workDir.resolve("tool-calls.jsonl");
    }

    private static void appendAudit(Path workDir, String step, String tool) throws Exception {
        String line = MAPPER.writeValueAsString(Map.of(
                "step", step,
                "tool", tool,
                "process", processOwner())) + System.lineSeparator();
        Files.writeString(audit(workDir), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long auditCount(Path workDir) throws Exception {
        if (!Files.isRegularFile(audit(workDir))) return 0;
        try (var lines = Files.lines(audit(workDir))) {
            return lines.count();
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String processOwner() {
        return "jvm-" + ProcessHandle.current().pid();
    }
}
