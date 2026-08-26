package com.mewcode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mewcode.agent.AgentEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Captures one agent run, classifies failures, and feeds the regression dataset. */
public final class TraceRecorder implements Consumer<AgentEvent> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String workDir;
    private TraceRecord trace;
    private boolean completed;

    public TraceRecorder(String workDir) {
        this.workDir = workDir;
    }

    public synchronized TraceRecord begin(String prompt, String model, String sessionId,
                                          Map<String, Object> metadata) {
        trace = new TraceRecord();
        trace.setRunId(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8));
        trace.setPrompt(prompt);
        trace.setModel(model);
        trace.setSessionId(sessionId);
        trace.setStartedAt(System.currentTimeMillis());
        trace.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        completed = false;
        return trace;
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        if (trace == null || completed) return;
        switch (event) {
            case AgentEvent.StreamText value -> trace.setOutputChars(trace.getOutputChars() + value.text().length());
            case AgentEvent.ToolUseEvent value -> {
                if (value.args() != null && !value.args().isEmpty()) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("toolId", value.toolId());
                    item.put("tool", value.toolName());
                    item.put("args", value.args());
                    trace.getToolCalls().add(item);
                }
            }
            case AgentEvent.ToolResultEvent value -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("toolId", value.toolId());
                item.put("tool", value.toolName());
                item.put("output", truncate(value.output(), 2_000));
                item.put("isError", value.isError());
                item.put("elapsedSeconds", value.elapsed());
                trace.getToolResults().add(item);
            }
            case AgentEvent.UsageEvent value -> {
                trace.setInputTokens(value.inputTokens());
                trace.setOutputTokens(value.outputTokens());
            }
            case AgentEvent.CompactEvent ignored -> trace.setCompactions(trace.getCompactions() + 1);
            case AgentEvent.RetryEvent value -> trace.getRetries().add(value.reason());
            case AgentEvent.ErrorEvent value -> trace.getErrors().add(value.message());
            case AgentEvent.LoopComplete value -> finish(value.totalTurns());
            default -> { }
        }
    }

    public synchronized TraceRecord finish(int turns) {
        if (trace == null || completed) return trace;
        completed = true;
        trace.setTurns(turns);
        trace.setFinishedAt(System.currentTimeMillis());
        trace.setDurationMs(trace.getFinishedAt() - trace.getStartedAt());
        trace.setFailureTypes(FailureClassifier.classify(trace));
        trace.setSuccess(trace.getErrors().isEmpty() && turns > 0
                && !trace.getFailureTypes().contains(FailureType.VERIFICATION_FAILURE));
        persist(trace);
        new RegressionDatasetStore(workDir).append(trace);
        return trace;
    }

    public synchronized TraceRecord current() { return trace; }

    private void persist(TraceRecord value) {
        Path path = Path.of(workDir, ".codeflow", "traces", value.getRunId() + ".json");
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write trace " + path, e);
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
