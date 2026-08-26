package com.mewcode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.A2aAgentConfig;
import com.mewcode.durable.DurableTaskState;
import com.mewcode.durable.DurableTaskStore;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovers and delegates work to configured remote A2A agents. */
public final class A2aDelegateTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, A2aAgentConfig> agents;
    private final DurableTaskStore durableStore;

    public A2aDelegateTool(List<A2aAgentConfig> configs, DurableTaskStore durableStore) {
        this.agents = new LinkedHashMap<>();
        if (configs != null) configs.stream().filter(A2aAgentConfig::isEnabled)
                .forEach(config -> agents.put(config.getName(), config));
        this.durableStore = durableStore;
    }

    @Override public String name() { return "A2ADelegate"; }

    @Override public String description() {
        return "Discover configured A2A 1.0 remote agents or delegate a task using Agent Card capability "
                + "discovery, durable task status polling, and Artifact return.";
    }

    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public boolean shouldDefer() { return true; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string", "enum", List.of("list", "discover", "delegate")),
                                "agent", Map.of("type", "string"),
                                "task", Map.of("type", "string"),
                                "durable_task_id", Map.of("type", "string")
                        ),
                        "required", List.of("action")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            String action = args.get("action") instanceof String value ? value : "";
            if ("list".equals(action)) {
                return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(agents.keySet()));
            }
            String name = args.get("agent") instanceof String value ? value : "";
            A2aAgentConfig config = agents.get(name);
            if (config == null) return ToolResult.error("Unknown A2A agent: " + name);
            A2aClient client = new A2aClient(config);
            if ("discover".equals(action)) {
                return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(client.discover()));
            }
            if (!"delegate".equals(action)) return ToolResult.error("Unknown action: " + action);
            String prompt = args.get("task") instanceof String value ? value : "";
            if (prompt.isBlank()) return ToolResult.error("task is required");
            String durableId = args.get("durable_task_id") instanceof String value ? value : null;
            if (durableId != null && durableStore != null) {
                var task = durableStore.get(durableId).orElseThrow(() -> new IllegalArgumentException("durable task not found"));
                if (task.getState() == DurableTaskState.PLANNING) {
                    durableStore.transition(durableId, DurableTaskState.EXECUTING,
                            "delegated to A2A agent " + name, task.getVersion());
                }
            }
            A2aTaskResult result = client.delegate(prompt);
            if (durableId != null && durableStore != null) {
                durableStore.checkpoint(durableId, Map.of(
                        "remoteAgent", name,
                        "remoteTaskId", result.taskId() == null ? "direct-message" : result.taskId(),
                        "remoteState", result.state()
                ), "A2A task update");
            }
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return ToolResult.success("<untrusted-a2a-response agent=\"" + name + "\">\n"
                    + json + "\n</untrusted-a2a-response>");
        } catch (Exception e) {
            return ToolResult.error("A2A delegation failed: " + e.getMessage());
        }
    }
}
