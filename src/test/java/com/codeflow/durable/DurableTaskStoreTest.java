package com.codeflow.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DurableTaskStoreTest {
    @TempDir Path dir;

    @Test
    void persistsLifecycleCheckpointAndArtifact() throws Exception {
        var store = new DurableTaskStore(dir.toString());
        var created = store.create("fix and verify the repository", "session-1", "lead", 3);
        var planning = store.transition(created.getId(), DurableTaskState.PLANNING, "plan", created.getVersion());
        var executing = store.transition(created.getId(), DurableTaskState.EXECUTING, "approved", planning.getVersion());
        var checkpointed = store.checkpoint(created.getId(), Map.of("step", 2, "commit", "abc"), "tests next");
        var verifying = store.transition(created.getId(), DurableTaskState.VERIFYING, "implementation done",
                checkpointed.getVersion());
        var withArtifact = store.addArtifact(created.getId(), "report", "reports/eval.md", "text/markdown", Map.of());
        var completed = store.transition(created.getId(), DurableTaskState.COMPLETED, "tests passed",
                withArtifact.getVersion());

        var reloaded = new DurableTaskStore(dir.toString()).get(created.getId()).orElseThrow();
        assertEquals(DurableTaskState.COMPLETED, completed.getState());
        assertEquals(DurableTaskState.COMPLETED, reloaded.getState());
        assertEquals(2, reloaded.getCheckpoint().get("step"));
        assertEquals(1, reloaded.getArtifacts().size());
        assertTrue(Files.readAllLines(store.eventLog(created.getId())).size() >= 7);
        assertTrue(store.list(true).isEmpty());
    }

    @Test
    void resumesPausedTaskAtPreviousStage() {
        var store = new DurableTaskStore(dir.toString());
        var task = store.create("task", "s", null, 3);
        task = store.transition(task.getId(), DurableTaskState.PLANNING, null, task.getVersion());
        task = store.transition(task.getId(), DurableTaskState.EXECUTING, null, task.getVersion());
        task = store.transition(task.getId(), DurableTaskState.PAUSED, "user pause", task.getVersion());

        var resumed = store.resume(task.getId(), "process restarted");
        assertEquals(DurableTaskState.EXECUTING, resumed.getState());
    }

    @Test
    void recoversInFlightTaskWithoutRepeatingItsStage() throws Exception {
        var firstProcess = new DurableTaskStore(dir.toString());
        var task = firstProcess.create("task", "s", "process-1", 3);
        task = firstProcess.transition(task.getId(), DurableTaskState.PLANNING, null, task.getVersion());
        task = firstProcess.transition(task.getId(), DurableTaskState.EXECUTING, null, task.getVersion());
        task = firstProcess.checkpoint(task.getId(), Map.of("completedSteps", java.util.List.of("edit-file")),
                "safe boundary");

        var restartedProcess = new DurableTaskStore(dir.toString());
        var recovered = restartedProcess.recover(
                task.getId(), "process-2", "runtime restarted", task.getVersion());

        assertEquals(DurableTaskState.EXECUTING, recovered.getState());
        assertEquals("process-2", recovered.getOwner());
        assertEquals(java.util.List.of("edit-file"), recovered.getCheckpoint().get("completedSteps"));
        assertTrue(Files.readString(restartedProcess.eventLog(task.getId())).contains("\"type\":\"recovered\""));
    }

    @Test
    void rejectsInvalidAndStaleTransitions() {
        var store = new DurableTaskStore(dir.toString());
        var task = store.create("task", null, null, 3);
        assertThrows(IllegalStateException.class, () -> store.transition(
                task.getId(), DurableTaskState.COMPLETED, "skip", task.getVersion()));
        var planning = store.transition(task.getId(), DurableTaskState.PLANNING, null, task.getVersion());
        assertThrows(IllegalStateException.class, () -> store.transition(
                task.getId(), DurableTaskState.EXECUTING, null, task.getVersion()));
        assertEquals(DurableTaskState.PLANNING, planning.getState());
    }

    @Test
    void retryBudgetEndsInFailedState() {
        var store = new DurableTaskStore(dir.toString());
        var task = store.create("task", null, null, 1);
        task = store.transition(task.getId(), DurableTaskState.PLANNING, null, task.getVersion());
        task = store.transition(task.getId(), DurableTaskState.FAILED_RETRYABLE, "boom", task.getVersion());
        assertEquals(DurableTaskState.FAILED, task.getState());
        assertEquals("boom", task.getLastError());
    }
}
