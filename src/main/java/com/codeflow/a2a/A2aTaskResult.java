package com.codeflow.a2a;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Normalized task or direct-message response returned by a remote A2A agent. */
public record A2aTaskResult(
        String taskId,
        String contextId,
        String state,
        String message,
        List<Artifact> artifacts,
        JsonNode raw
) {
    public record Artifact(String id, String name, String description, List<Part> parts) {}
    public record Part(String text, String url, JsonNode data, String mediaType, String filename) {}

    public boolean terminal() {
        return switch (state == null ? "" : state) {
            case "TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_REJECTED",
                    "MESSAGE" -> true;
            default -> false;
        };
    }
}
