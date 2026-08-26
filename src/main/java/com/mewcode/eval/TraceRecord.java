package com.mewcode.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable trace summary; intentionally excludes hidden model reasoning. */
public class TraceRecord {
    private String runId;
    private String sessionId;
    private String prompt;
    private String model;
    private long startedAt;
    private long finishedAt;
    private long durationMs;
    private int turns;
    private int inputTokens;
    private int outputTokens;
    private int outputChars;
    private int compactions;
    private boolean success;
    private List<Map<String, Object>> toolCalls = new ArrayList<>();
    private List<Map<String, Object>> toolResults = new ArrayList<>();
    private List<String> retries = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
    private List<FailureType> failureTypes = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public int getOutputChars() { return outputChars; }
    public void setOutputChars(int outputChars) { this.outputChars = outputChars; }
    public int getCompactions() { return compactions; }
    public void setCompactions(int compactions) { this.compactions = compactions; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public List<Map<String, Object>> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<Map<String, Object>> toolCalls) { this.toolCalls = toolCalls; }
    public List<Map<String, Object>> getToolResults() { return toolResults; }
    public void setToolResults(List<Map<String, Object>> toolResults) { this.toolResults = toolResults; }
    public List<String> getRetries() { return retries; }
    public void setRetries(List<String> retries) { this.retries = retries; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
    public List<FailureType> getFailureTypes() { return failureTypes; }
    public void setFailureTypes(List<FailureType> failureTypes) { this.failureTypes = failureTypes; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
