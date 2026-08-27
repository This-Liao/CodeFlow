package com.codeflow.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeflow.config.ContextPolicyConfig;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.tool.Tool;
import com.codeflow.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stage-aware context assembler for tool schemas and long-term memory. */
public final class ContextPolicy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "then", "when",
            "一个", "这个", "进行", "以及", "需要", "可以", "然后", "当前", "项目");

    private final ContextPolicyConfig config;

    public ContextPolicy(ContextPolicyConfig config) {
        this.config = config == null ? new ContextPolicyConfig() : config;
    }

    public Selection apply(ToolRegistry registry, String protocol, ConversationManager conversation) {
        if (config.isIncludeDeferredTools()) {
            registry.listTools().forEach(tool -> registry.markDiscovered(tool.name()));
        }
        if (!config.isEnabled()) {
            registry.setContextOmittedTools(Set.of());
            return new Selection(inferStage(conversation), List.of(), registry.getAllSchemas(protocol).size(), 0);
        }
        String recent = recentContext(conversation);
        ContextStage stage = inferStage(recent);
        Set<String> tokens = tokens(recent);
        var scored = new ArrayList<ScoredTool>();
        for (Tool tool : registry.listTools()) {
            if (tool.shouldDefer() && !registry.isDiscovered(tool.name())) continue;
            scored.add(new ScoredTool(tool, score(tool, stage, tokens), schemaChars(tool)));
        }
        scored.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                .thenComparing(value -> value.tool().name()));

        int maxCount = Math.max(4, config.getMaxToolSchemas());
        int maxChars = Math.max(4_000, config.getMaxSchemaChars());
        int count = 0;
        int chars = 0;
        var selected = new LinkedHashSet<String>();
        for (ScoredTool item : scored) {
            boolean essential = isEssential(item.tool().name());
            if (!essential && (count >= maxCount || chars + item.schemaChars() > maxChars)) continue;
            selected.add(item.tool().name());
            count++;
            chars += item.schemaChars();
        }
        var omitted = new LinkedHashSet<String>();
        for (ScoredTool item : scored) {
            if (!selected.contains(item.tool().name()) && !registry.isDiscovered(item.tool().name())) {
                omitted.add(item.tool().name());
            }
        }
        registry.setContextOmittedTools(omitted);
        return new Selection(stage, List.copyOf(omitted), selected.size(), chars);
    }

    public String selectMemory(String memory, ConversationManager conversation) {
        if (!config.isEnabled() || memory == null || memory.length() <= config.getMaxMemoryChars()) return memory;
        int budget = Math.max(1_000, config.getMaxMemoryChars());
        Set<String> query = tokens(recentContext(conversation));
        String[] paragraphs = memory.split("\\R\\s*\\R");
        var scored = new ArrayList<ScoredParagraph>();
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].strip();
            int overlap = 0;
            Set<String> paragraphTokens = tokens(paragraph);
            for (String token : query) if (paragraphTokens.contains(token)) overlap++;
            scored.add(new ScoredParagraph(i, paragraph, overlap));
        }
        scored.sort(Comparator.comparingInt(ScoredParagraph::score).reversed()
                .thenComparing(Comparator.comparingInt(ScoredParagraph::index).reversed()));
        var keep = new HashSet<Integer>();
        int chars = 0;
        for (ScoredParagraph item : scored) {
            if (item.text().isBlank() || chars + item.text().length() > budget) continue;
            keep.add(item.index());
            chars += item.text().length() + 2;
        }
        var result = new ArrayList<String>();
        for (int i = 0; i < paragraphs.length; i++) if (keep.contains(i)) result.add(paragraphs[i].strip());
        return String.join("\n\n", result);
    }

    public ContextStage inferStage(ConversationManager conversation) {
        return inferStage(recentContext(conversation));
    }

    public ContextStage inferStage(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (containsAny(text, "error", "failed", "exception", "retry", "recover", "错误", "失败", "恢复", "重试")) {
            return ContextStage.RECOVERING;
        }
        if (containsAny(text, "test", "verify", "check", "benchmark", "eval", "验证", "测试", "评测")) {
            return ContextStage.VERIFYING;
        }
        if (containsAny(text, "plan", "design", "architecture", "explore", "方案", "规划", "架构", "分析")) {
            return ContextStage.PLANNING;
        }
        return ContextStage.EXECUTING;
    }

    private static int score(Tool tool, ContextStage stage, Set<String> query) {
        String name = tool.name();
        if (isEssential(name)) return 100_000;
        String searchable = (name + " " + tool.description()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : query) if (searchable.contains(token)) score += 20;
        score += switch (stage) {
            case PLANNING -> containsAny(searchable, "read", "grep", "glob", "search", "task", "agent") ? 300 : 0;
            case EXECUTING -> containsAny(searchable, "write", "edit", "bash", "agent", "worktree", "task") ? 300 : 0;
            case VERIFYING -> containsAny(searchable, "bash", "test", "read", "grep", "eval", "durable") ? 300 : 0;
            case RECOVERING -> containsAny(searchable, "read", "bash", "task", "durable", "agent", "message") ? 300 : 0;
        };
        return score;
    }

    private static boolean isEssential(String name) {
        return "ToolSearch".equals(name) || "AskUser".equals(name) || "ExitPlanMode".equals(name);
    }

    private static int schemaChars(Tool tool) {
        try { return MAPPER.writeValueAsString(tool.schema()).length(); }
        catch (Exception ignored) { return 1_000; }
    }

    private static String recentContext(ConversationManager conversation) {
        var messages = conversation.getMessages();
        var out = new StringBuilder();
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.getContent() != null) out.append(message.getContent()).append('\n');
            if (message.getToolUses() != null) message.getToolUses().forEach(use ->
                    out.append(use.toolName()).append(' ').append(use.arguments()).append('\n'));
            if (message.getToolResults() != null) message.getToolResults().forEach(result ->
                    out.append(result.content(), 0, Math.min(result.content().length(), 500)).append('\n'));
        }
        return out.toString();
    }

    private static Set<String> tokens(String value) {
        if (value == null) return Set.of();
        var result = new HashSet<String>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) result.add(token);
        }
        return result;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    public record Selection(ContextStage stage, List<String> omittedTools, int selectedTools,
                            int estimatedSchemaChars) {}
    private record ScoredTool(Tool tool, int score, int schemaChars) {}
    private record ScoredParagraph(int index, String text, int score) {}
}
