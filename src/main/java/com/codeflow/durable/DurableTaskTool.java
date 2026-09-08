package com.codeflow.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeflow.tool.Tool;
import com.codeflow.tool.ToolCategory;
import com.codeflow.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Agent-facing control plane for durable tasks and checkpoints. */
public final class DurableTaskTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DurableTaskRepository store;

    public DurableTaskTool(DurableTaskRepository store) {
        this.store = store;
    }

    @Override public String name() { return "DurableTask"; }

    @Override public String description() {
        return "Create, inspect, checkpoint, pause, resume, transition, and attach artifacts to "
                + "long-running tasks. State survives process restarts.";
    }

    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override public boolean shouldDefer() { return true; }

    @Override
    public Map<String, Object> schema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of("type", "string", "enum", List.of(
                "create", "get", "list", "transition", "checkpoint", "resume", "artifact")));
        properties.put("task_id", Map.of("type", "string"));
        properties.put("prompt", Map.of("type", "string"));
        properties.put("session_id", Map.of("type", "string"));
        properties.put("owner", Map.of("type", "string"));
        properties.put("state", Map.of("type", "string"));
        properties.put("reason", Map.of("type", "string"));
        properties.put("checkpoint", Map.of("type", "object"));
        properties.put("artifact", Map.of("type", "object"));
        properties.put("recoverable_only", Map.of("type", "boolean"));
        properties.put("max_attempts", Map.of("type", "integer", "default", 3));
        properties.put("expected_version", Map.of("type", "integer"));
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("action")
                )
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        try {
            String action = string(args, "action");
            Object result = switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
                case "create" -> store.create(required(args, "prompt"), string(args, "session_id"),
                        string(args, "owner"), integer(args, "max_attempts", 3));
                case "get" -> store.get(required(args, "task_id"))
                        .orElseThrow(() -> new IllegalArgumentException("task not found"));
                case "list" -> store.list(bool(args, "recoverable_only", false));
                case "transition" -> store.transition(required(args, "task_id"),
                        DurableTaskState.valueOf(required(args, "state").toUpperCase(Locale.ROOT)),
                        string(args, "reason"), longValue(args, "expected_version"));
                case "checkpoint" -> store.checkpoint(required(args, "task_id"),
                        args.get("checkpoint") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                        string(args, "reason"));
                case "resume" -> store.resume(required(args, "task_id"), string(args, "reason"));
                case "artifact" -> {
                    Map<String, Object> artifact = args.get("artifact") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    yield store.addArtifact(required(args, "task_id"),
                            String.valueOf(artifact.getOrDefault("name", "artifact")),
                            String.valueOf(artifact.getOrDefault("uri", "")),
                            String.valueOf(artifact.getOrDefault("mediaType", "application/octet-stream")),
                            artifact.get("metadata") instanceof Map<?, ?> map
                                    ? (Map<String, Object>) map : Map.of());
                }
                default -> throw new IllegalArgumentException("unknown action: " + action);
            };
            return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            return ToolResult.error("DurableTask error: " + e.getMessage());
        }
    }

    private static String required(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String string(Map<String, Object> args, String key) {
        return args.get(key) instanceof String value ? value : null;
    }
    private static int integer(Map<String, Object> args, String key, int fallback) {
        return args.get(key) instanceof Number value ? value.intValue() : fallback;
    }
    private static Long longValue(Map<String, Object> args, String key) {
        return args.get(key) instanceof Number value ? value.longValue() : null;
    }
    private static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        return args.get(key) instanceof Boolean value ? value : fallback;
    }
}
