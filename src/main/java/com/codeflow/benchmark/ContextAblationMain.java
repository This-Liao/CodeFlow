package com.codeflow.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeflow.config.ContextPolicyConfig;
import com.codeflow.context.ContextPolicy;
import com.codeflow.context.ContextStage;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.tool.Tool;
import com.codeflow.tool.ToolCategory;
import com.codeflow.tool.ToolRegistry;
import com.codeflow.tool.ToolResult;
import com.codeflow.tool.impl.ToolSearchTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic 24-task ablation for full, deferred, and stage-aware tool context. */
public final class ContextAblationMain {
    public static final String RESULT_PREFIX = "CODEFLOW_CONTEXT_BENCHMARK_JSON=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextAblationMain() {}

    public static void main(String[] args) throws Exception {
        List<TaskCase> tasks = tasks();
        List<VariantResult> results = List.of(
                runVariant("full-tool-injection", Variant.FULL, tasks),
                runVariant("deferred-tool", Variant.DEFERRED, tasks),
                runVariant("context-policy", Variant.CONTEXT, tasks));
        double fullChars = results.getFirst().averageSchemaChars();
        VariantResult context = results.getLast();
        double contextReduction = 100.0 * (1.0 - context.averageSchemaChars() / fullChars);
        if (context.successRate() < 100.0 || context.stageAccuracy() < 100.0
                || contextReduction < 60.0 || context.toolErrors() != 0) {
            throw new IllegalStateException(
                    "context regression: success=" + context.successRate()
                            + ", stageAccuracy=" + context.stageAccuracy()
                            + ", schemaReduction=" + contextReduction
                            + ", toolErrors=" + context.toolErrors());
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("result", "PASS");
        root.put("taskCount", tasks.size());
        root.put("toolCatalogSize", 46);
        root.put("contextPolicyMaxSchemas", 12);
        ArrayNode variants = root.putArray("variants");
        for (VariantResult result : results) {
            ObjectNode row = variants.addObject();
            row.put("name", result.name());
            row.put("successRate", round(result.successRate()));
            row.put("stageAccuracy", round(result.stageAccuracy()));
            row.put("initialToolRecall", round(result.initialToolRecall()));
            row.put("averageInitialToolSchemas", round(result.averageInitialToolSchemas()));
            row.put("averageSchemaChars", round(result.averageSchemaChars()));
            row.put("estimatedInputTokens", Math.round(result.averageSchemaChars() / 4.0));
            row.put("schemaReductionPct", round(100.0 * (1.0 - result.averageSchemaChars() / fullChars)));
            row.put("toolSearchCalls", result.toolSearchCalls());
            row.put("toolErrors", result.toolErrors());
            row.put("p95SelectionMicros", result.p95SelectionMicros());
        }
        System.out.println(RESULT_PREFIX + MAPPER.writeValueAsString(root));
    }

    private static VariantResult runVariant(String name, Variant variant, List<TaskCase> tasks) throws Exception {
        int successes = 0;
        int stageHits = 0;
        int requiredHits = 0;
        int requiredTotal = 0;
        int schemaCount = 0;
        long schemaChars = 0;
        int searchCalls = 0;
        int toolErrors = 0;
        List<Long> latencyMicros = new ArrayList<>();

        for (TaskCase task : tasks) {
            ToolRegistry registry = registry();
            if (variant == Variant.FULL) {
                registry.listTools().forEach(tool -> registry.markDiscovered(tool.name()));
            }
            ContextPolicyConfig config = new ContextPolicyConfig();
            config.setEnabled(variant == Variant.CONTEXT);
            config.setMaxToolSchemas(12);
            config.setMaxSchemaChars(20_000);
            ConversationManager conversation = new ConversationManager();
            conversation.addUserMessage(task.prompt());

            long started = System.nanoTime();
            ContextPolicy.Selection selection =
                    new ContextPolicy(config).apply(registry, "anthropic", conversation);
            latencyMicros.add((System.nanoTime() - started) / 1_000);
            if (selection.stage() == task.stage()) stageHits++;

            List<Map<String, Object>> initial = registry.getAllSchemas("anthropic");
            Set<String> initialNames = schemaNames(initial);
            schemaCount += initial.size();
            schemaChars += MAPPER.writeValueAsString(initial).length();
            requiredTotal += task.requiredTools().size();
            for (String required : task.requiredTools()) {
                if (initialNames.contains(required)) requiredHits++;
            }

            List<String> missing = task.requiredTools().stream()
                    .filter(required -> !initialNames.contains(required))
                    .toList();
            if (!missing.isEmpty()) {
                searchCalls++;
                ToolResult found = registry.get("ToolSearch").execute(
                        Map.of("query", "select:" + String.join(",", missing)));
                if (found.isError()) toolErrors++;
            }
            Set<String> resolved = schemaNames(registry.getAllSchemas("anthropic"));
            if (resolved.containsAll(task.requiredTools())) successes++;
        }

        latencyMicros.sort(Comparator.naturalOrder());
        int p95Index = Math.min(latencyMicros.size() - 1,
                (int) Math.ceil(latencyMicros.size() * 0.95) - 1);
        return new VariantResult(
                name,
                100.0 * successes / tasks.size(),
                100.0 * stageHits / tasks.size(),
                100.0 * requiredHits / requiredTotal,
                (double) schemaCount / tasks.size(),
                (double) schemaChars / tasks.size(),
                searchCalls,
                toolErrors,
                latencyMicros.get(p95Index));
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        List<Spec> relevant = List.of(
                spec("ReadFile", "read source files, logs, and configuration", false),
                spec("WriteFile", "write a new source or configuration file", false),
                spec("EditFile", "edit and modify an existing source file", false),
                spec("Bash", "run commands, Gradle tests, builds, and verification", false),
                spec("Glob", "discover files by repository path pattern", false),
                spec("Grep", "search source symbols, text, and architecture references", false),
                spec("Agent", "delegate repository analysis to a local subagent", false),
                spec("DurableTask", "resume recover retry checkpoint and inspect durable tasks", false),
                spec("EvalReport", "evaluate traces and enforce regression quality gates", false),
                spec("RunTests", "run unit integration and regression tests", false),
                spec("GitStatus", "inspect git changes branch and repository status", false),
                spec("Worktree", "create isolated git worktrees for implementation", false),
                spec("MemoryRecall", "recall relevant long-term project memory", false),
                spec("InspectTrace", "inspect agent trace errors latency and tool calls", false),
                spec("PlanRepository", "plan repository architecture and implementation steps", false),
                spec("AskUser", "request approval or missing requirements from the user", false),
                spec("A2ADelegate", "delegate work to a remote A2A agent and fetch artifacts", true),
                spec("WebFetch", "fetch external documentation and HTTP resources", true),
                spec("DatabaseQuery", "query a SQL database for application data", true),
                spec("OpenTelemetryExport", "export correlated traces and spans to OpenTelemetry", true));
        relevant.forEach(value -> registry.register(tool(value)));
        for (int i = 1; i <= 25; i++) {
            registry.register(tool(spec(
                    "Connector%02d".formatted(i),
                    "specialized deferred enterprise connector number " + i
                            + " with authentication pagination filtering and structured results",
                    true)));
        }
        registry.register(new ToolSearchTool(registry, "anthropic"));
        if (registry.listTools().size() != 46) {
            throw new IllegalStateException("benchmark catalog must contain 46 tools");
        }
        return registry;
    }

    private static Tool tool(Spec spec) {
        return new Tool() {
            @Override public String name() { return spec.name(); }
            @Override public String description() { return spec.description(); }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public boolean shouldDefer() { return spec.deferred(); }
            @Override public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
            @Override public Map<String, Object> schema() {
                var properties = new java.util.LinkedHashMap<String, Object>();
                for (int i = 0; i < 8; i++) {
                    properties.put("parameter_" + i, Map.of(
                            "type", "string",
                            "description", "Detailed parameter " + i + " for " + spec.name()
                                    + " used by repository-scale agent tasks"));
                }
                return Map.of(
                        "name", spec.name(),
                        "description", spec.description(),
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", List.of("parameter_0")));
            }
        };
    }

    private static Set<String> schemaNames(List<Map<String, Object>> schemas) {
        var names = new LinkedHashSet<String>();
        for (Map<String, Object> schema : schemas) {
            if (schema.get("name") instanceof String name) names.add(name);
        }
        return names;
    }

    private static List<TaskCase> tasks() {
        return List.of(
                task("Analyze the repository architecture and read core modules", ContextStage.PLANNING, "ReadFile", "Grep"),
                task("Explore Java symbols and file layout before changing code", ContextStage.PLANNING, "Glob", "Grep"),
                task("Design an implementation plan for the repository", ContextStage.PLANNING, "PlanRepository", "ReadFile"),
                task("Plan dependency analysis across configuration files", ContextStage.PLANNING, "ReadFile", "Glob"),
                task("Plan a multi-agent architecture and inspect current agents", ContextStage.PLANNING, "Agent", "ReadFile"),
                task("Plan isolated worktree changes and inspect git status", ContextStage.PLANNING, "Worktree", "GitStatus"),
                task("Modify the existing Java implementation", ContextStage.EXECUTING, "EditFile", "ReadFile"),
                task("Create a new configuration file", ContextStage.EXECUTING, "WriteFile"),
                task("Run a shell command to build the module", ContextStage.EXECUTING, "Bash"),
                task("Delegate static analysis to a remote A2A agent", ContextStage.EXECUTING, "A2ADelegate"),
                task("Fetch external API documentation", ContextStage.EXECUTING, "WebFetch"),
                task("Query application records from the SQL database", ContextStage.EXECUTING, "DatabaseQuery"),
                task("Export correlated agent spans to telemetry", ContextStage.EXECUTING, "OpenTelemetryExport"),
                task("Create an isolated git worktree for the fix", ContextStage.EXECUTING, "Worktree"),
                task("Recall project memory before editing the module", ContextStage.EXECUTING, "MemoryRecall"),
                task("Run Gradle tests and verify the build", ContextStage.VERIFYING, "RunTests", "Bash"),
                task("Inspect failed test logs and check source references", ContextStage.RECOVERING, "ReadFile", "Grep"),
                task("Evaluate agent traces and benchmark the regression", ContextStage.VERIFYING, "EvalReport", "InspectTrace"),
                task("Check git status after tests complete", ContextStage.VERIFYING, "GitStatus"),
                task("Test and verify the remote A2A delegation", ContextStage.VERIFYING, "A2ADelegate", "RunTests"),
                task("Recover the durable checkpoint after a process failure", ContextStage.RECOVERING, "DurableTask", "ReadFile"),
                task("Retry the failed command from the saved task", ContextStage.RECOVERING, "DurableTask", "Bash"),
                task("Recover a failed subagent using trace evidence", ContextStage.RECOVERING, "Agent", "InspectTrace"),
                task("Resume the failed edit without repeating completed work", ContextStage.RECOVERING, "DurableTask", "EditFile"));
    }

    private static TaskCase task(String prompt, ContextStage stage, String... required) {
        return new TaskCase(prompt, stage, Set.copyOf(Arrays.asList(required)));
    }

    private static Spec spec(String name, String description, boolean deferred) {
        return new Spec(name, description, deferred);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private enum Variant { FULL, DEFERRED, CONTEXT }
    private record Spec(String name, String description, boolean deferred) {}
    private record TaskCase(String prompt, ContextStage stage, Set<String> requiredTools) {}
    private record VariantResult(
            String name,
            double successRate,
            double stageAccuracy,
            double initialToolRecall,
            double averageInitialToolSchemas,
            double averageSchemaChars,
            int toolSearchCalls,
            int toolErrors,
            long p95SelectionMicros) {}
}
