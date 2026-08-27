package com.codeflow.eval;

import com.codeflow.agent.AgentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceEvalTest {
    @TempDir Path dir;

    @Test
    void classifiesAndAddsDeduplicatedRegressionCase() throws Exception {
        var recorder = new TraceRecorder(dir.toString());
        recorder.begin("fix the failing build", "model", "session", Map.of());
        for (int i = 0; i < 3; i++) {
            recorder.accept(new AgentEvent.ToolUseEvent("id-" + i, "Bash", Map.of("command", "gradle test")));
        }
        recorder.accept(new AgentEvent.ToolResultEvent("id-2", "Bash", "Gradle test failed", true, 1.2));
        recorder.accept(new AgentEvent.UsageEvent(100, 20));
        recorder.accept(new AgentEvent.LoopComplete(2));

        var trace = recorder.current();
        assertTrue(trace.getFailureTypes().contains(FailureType.REPEATED_TOOL_CALL));
        assertTrue(trace.getFailureTypes().contains(FailureType.VERIFICATION_FAILURE));
        assertFalse(trace.isSuccess());
        Path dataset = new RegressionDatasetStore(dir.toString()).path();
        assertEquals(1, Files.readAllLines(dataset).size());
        assertFalse(new RegressionDatasetStore(dir.toString()).append(trace));
    }

    @Test
    void generatesMetricsAndDetectsRegression() throws Exception {
        Path baselineTraces = dir.resolve("baseline");
        Path candidateTraces = dir.resolve("candidate");
        Files.createDirectories(baselineTraces);
        Files.createDirectories(candidateTraces);
        writeTrace(baselineTraces.resolve("one.json"), true, 100, false);
        var baselineOut = EvalReportGenerator.write(baselineTraces, null, dir.resolve("baseline-report"));

        writeTrace(candidateTraces.resolve("one.json"), false, 200, true);
        var report = EvalReportGenerator.generate(candidateTraces, baselineOut.json());
        assertEquals(0.0, report.successRate());
        assertFalse(report.passedRegressionGate());
        assertTrue(report.regressions().stream().anyMatch(value -> value.contains("success rate")));
    }

    private static void writeTrace(Path path, boolean success, long duration, boolean toolError) throws Exception {
        var trace = new TraceRecord();
        trace.setRunId(path.getFileName().toString());
        trace.setSuccess(success);
        trace.setDurationMs(duration);
        trace.setInputTokens(100);
        trace.setOutputTokens(10);
        if (toolError) trace.getToolResults().add(Map.of("isError", true));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(path.toFile(), trace);
    }
}
