package com.mewcode.durable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-persisted snapshot for one long-horizon agent task. */
public class DurableTask {
    private String id;
    private String sessionId;
    private String prompt;
    private DurableTaskState state = DurableTaskState.CREATED;
    private DurableTaskState resumeState;
    private int attempt;
    private int maxAttempts = 3;
    private long version;
    private long createdAt;
    private long updatedAt;
    private String owner;
    private String lastError;
    private Map<String, Object> checkpoint = new LinkedHashMap<>();
    private List<Map<String, Object>> artifacts = new ArrayList<>();

    public DurableTask() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public DurableTaskState getState() { return state; }
    public void setState(DurableTaskState state) { this.state = state; }
    public DurableTaskState getResumeState() { return resumeState; }
    public void setResumeState(DurableTaskState resumeState) { this.resumeState = resumeState; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Map<String, Object> getCheckpoint() { return checkpoint; }
    public void setCheckpoint(Map<String, Object> checkpoint) {
        this.checkpoint = checkpoint == null ? new LinkedHashMap<>() : new LinkedHashMap<>(checkpoint);
    }
    public List<Map<String, Object>> getArtifacts() { return artifacts; }
    public void setArtifacts(List<Map<String, Object>> artifacts) {
        this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
    }
}
