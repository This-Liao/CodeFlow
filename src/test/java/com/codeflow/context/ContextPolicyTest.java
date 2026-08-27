package com.codeflow.context;

import com.codeflow.config.ContextPolicyConfig;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.tool.Tool;
import com.codeflow.tool.ToolCategory;
import com.codeflow.tool.ToolRegistry;
import com.codeflow.tool.ToolResult;
import com.codeflow.tool.impl.ToolSearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextPolicyTest {
    @Test
    void prioritizesVerificationToolsAndDefersTheRest() {
        var config = new ContextPolicyConfig();
        config.setMaxToolSchemas(5);
        config.setMaxSchemaChars(8_000);
        var registry = new ToolRegistry();
        registry.register(tool("Bash", "Run tests and Gradle verification"));
        registry.register(tool("ReadFile", "Read failure logs"));
        for (int i = 0; i < 20; i++) registry.register(tool("Unrelated" + i, "Unrelated capability " + i));
        registry.register(new ToolSearchTool(registry, "anthropic"));
        var conversation = new ConversationManager();
        conversation.addUserMessage("Run the Gradle tests and verify the build");

        var selection = new ContextPolicy(config).apply(registry, "anthropic", conversation);
        var names = registry.getAllSchemas("anthropic").stream().map(s -> (String) s.get("name")).toList();
        assertEquals(ContextStage.VERIFYING, selection.stage());
        assertTrue(names.contains("ToolSearch"));
        assertTrue(names.contains("Bash"));
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.size() <= 5);
        assertFalse(selection.omittedTools().isEmpty());
    }

    @Test
    void toolSearchCanRestorePolicyDeferredTool() {
        var config = new ContextPolicyConfig();
        config.setMaxToolSchemas(4);
        var registry = new ToolRegistry();
        registry.register(tool("Bash", "test"));
        for (int i = 0; i < 8; i++) registry.register(tool("Other" + i, "other"));
        var search = new ToolSearchTool(registry, "anthropic");
        registry.register(search);
        var conversation = new ConversationManager();
        conversation.addUserMessage("verify tests");
        new ContextPolicy(config).apply(registry, "anthropic", conversation);
        String omitted = registry.getContextOmittedTools().iterator().next();

        assertFalse(search.execute(Map.of("query", "select:" + omitted)).isError());
        assertTrue(registry.isDiscovered(omitted));
        assertTrue(registry.getAllSchemas("anthropic").stream()
                .anyMatch(schema -> omitted.equals(schema.get("name"))));
    }

    @Test
    void canIncludeDeferredToolsForAblation() {
        var config = new ContextPolicyConfig();
        config.setEnabled(false);
        config.setIncludeDeferredTools(true);
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "RemoteAgent"; }
            public String description() { return "deferred remote agent"; }
            public ToolCategory category() { return ToolCategory.READ; }
            public boolean shouldDefer() { return true; }
            public Map<String, Object> schema() {
                return Map.of("name", name(), "description", description(),
                        "input_schema", Map.of("type", "object", "properties", Map.of()));
            }
            public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
        });
        var conversation = new ConversationManager();
        conversation.addUserMessage("compare full tool context");

        new ContextPolicy(config).apply(registry, "anthropic", conversation);

        assertTrue(registry.isDiscovered("RemoteAgent"));
        assertTrue(registry.getAllSchemas("anthropic").stream()
                .anyMatch(schema -> "RemoteAgent".equals(schema.get("name"))));
    }

    @Test
    void selectsRelevantMemoryWithinBudget() {
        var config = new ContextPolicyConfig();
        config.setMaxMemoryChars(1_000);
        String relevant = "Gradle verification uses ./gradlew test and stores reports.";
        String memory = String.join("\n\n", relevant, "x".repeat(1_500), "y".repeat(1_500));
        var conversation = new ConversationManager();
        conversation.addUserMessage("Please run Gradle test verification");
        String selected = new ContextPolicy(config).selectMemory(memory, conversation);
        assertTrue(selected.contains("Gradle verification"));
        assertTrue(selected.length() <= 1_000);
    }

    private static Tool tool(String name, String description) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public ToolCategory category() { return ToolCategory.READ; }
            public Map<String, Object> schema() {
                return Map.of("name", name, "description", description,
                        "input_schema", Map.of("type", "object", "properties", Map.of()));
            }
            public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
        };
    }
}
