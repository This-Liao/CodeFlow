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
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Stage-aware context assembler for tool schemas and long-term memory. */
public final class ContextPolicy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "then", "when",
            "一个", "这个", "进行", "以及", "需要", "可以", "然后", "当前", "项目");

    private final ContextPolicyConfig config;
    private final EmbeddingProvider embeddingProvider;
    private final Map<String, double[]> toolVectorCache = new ConcurrentHashMap<>();

    public ContextPolicy(ContextPolicyConfig config) {
        this(config, providerFor(config));
    }

    public ContextPolicy(ContextPolicyConfig config, EmbeddingProvider embeddingProvider) {
        this.config = config == null ? new ContextPolicyConfig() : config;
        this.embeddingProvider = embeddingProvider;
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
        List<Tool> candidates = registry.listTools().stream()
                .filter(tool -> !tool.shouldDefer() || registry.isDiscovered(tool.name()))
                .toList();
        Map<String, Double> vectorScores = vectorSimilarities(recent, candidates);
        var scored = new ArrayList<ScoredTool>();
        for (Tool tool : candidates) {
            scored.add(new ScoredTool(tool,
                    score(tool, stage, tokens, vectorScores.get(tool.name())), schemaChars(tool)));
        }
        scored.sort(Comparator.comparingDouble(ScoredTool::score).reversed()
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
        List<String> paragraphList = java.util.Arrays.stream(paragraphs)
                .map(String::strip).toList();
        List<Double> vectorScores = paragraphSimilarities(recentContext(conversation), paragraphList);
        var scored = new ArrayList<ScoredParagraph>();
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].strip();
            int overlap = 0;
            Set<String> paragraphTokens = tokens(paragraph);
            for (String token : query) if (paragraphTokens.contains(token)) overlap++;
            Double vector = i < vectorScores.size() ? vectorScores.get(i) : null;
            scored.add(new ScoredParagraph(i, paragraph,
                    blend(overlap * 20.0, vector, 100.0)));
        }
        scored.sort(Comparator.comparingDouble(ScoredParagraph::score).reversed()
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

    private double score(Tool tool, ContextStage stage, Set<String> query, Double vectorSimilarity) {
        String name = tool.name();
        if (isEssential(name)) return 100_000;
        String searchable = (name + " " + tool.description()).toLowerCase(Locale.ROOT);
        int lexical = 0;
        for (String token : query) if (searchable.contains(token)) lexical += 20;
        int stageScore = switch (stage) {
            case PLANNING -> containsAny(searchable, "read", "grep", "glob", "search", "task", "agent") ? 300 : 0;
            case EXECUTING -> containsAny(searchable, "write", "edit", "bash", "agent", "worktree", "task") ? 300 : 0;
            case VERIFYING -> containsAny(searchable, "bash", "test", "read", "grep", "eval", "durable") ? 300 : 0;
            case RECOVERING -> containsAny(searchable, "read", "bash", "task", "durable", "agent", "message") ? 300 : 0;
        };
        return stageScore + blend(lexical, vectorSimilarity, 400.0);
    }

    private double blend(double lexicalScore, Double vectorSimilarity, double vectorScale) {
        Strategy strategy = strategy();
        if (strategy == Strategy.LEXICAL || vectorSimilarity == null
                || !Double.isFinite(vectorSimilarity)) {
            return lexicalScore;
        }
        double vectorScore = Math.max(0, vectorSimilarity) * vectorScale;
        if (strategy == Strategy.VECTOR) return vectorScore;
        double lexicalWeight = clamp(config.getLexicalWeight());
        double vectorWeight = clamp(config.getVectorWeight());
        double total = lexicalWeight + vectorWeight;
        if (total <= 0) return lexicalScore;
        return lexicalScore * lexicalWeight / total + vectorScore * vectorWeight / total;
    }

    private Map<String, Double> vectorSimilarities(String query, List<Tool> tools) {
        if (embeddingProvider == null || strategy() == Strategy.LEXICAL || tools.isEmpty()) return Map.of();
        try {
            var inputs = new ArrayList<String>();
            inputs.add(query);
            var missing = new ArrayList<Tool>();
            for (Tool tool : tools) {
                String key = toolVectorKey(tool);
                if (!toolVectorCache.containsKey(key)) {
                    missing.add(tool);
                    inputs.add(toolText(tool));
                }
            }
            List<double[]> embedded = embeddingProvider.embed(inputs);
            if (embedded.size() != inputs.size()) return Map.of();
            double[] queryVector = embedded.getFirst();
            for (int i = 0; i < missing.size(); i++) {
                toolVectorCache.put(toolVectorKey(missing.get(i)), embedded.get(i + 1));
            }
            var result = new HashMap<String, Double>();
            for (Tool tool : tools) {
                double[] vector = toolVectorCache.get(toolVectorKey(tool));
                if (vector != null) result.put(tool.name(), cosine(queryVector, vector));
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Double> paragraphSimilarities(String query, List<String> paragraphs) {
        if (embeddingProvider == null || strategy() == Strategy.LEXICAL || paragraphs.isEmpty()) return List.of();
        try {
            var inputs = new ArrayList<String>();
            inputs.add(query);
            inputs.addAll(paragraphs);
            List<double[]> embedded = embeddingProvider.embed(inputs);
            if (embedded.size() != inputs.size()) return List.of();
            double[] queryVector = embedded.getFirst();
            var result = new ArrayList<Double>(paragraphs.size());
            for (int i = 1; i < embedded.size(); i++) result.add(cosine(queryVector, embedded.get(i)));
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Strategy strategy() {
        String value = config.getStrategy() == null ? "hybrid"
                : config.getStrategy().strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "lexical" -> Strategy.LEXICAL;
            case "vector" -> Strategy.VECTOR;
            default -> Strategy.HYBRID;
        };
    }

    private static EmbeddingProvider providerFor(ContextPolicyConfig value) {
        ContextPolicyConfig config = value == null ? new ContextPolicyConfig() : value;
        var embedding = config.getEmbedding();
        if (embedding == null || !embedding.isEnabled()
                || "lexical".equalsIgnoreCase(config.getStrategy())) return null;
        if ("openai-compatible".equalsIgnoreCase(embedding.getProvider())
                || "openai".equalsIgnoreCase(embedding.getProvider())) {
            return new OpenAiEmbeddingProvider(embedding);
        }
        return new FeatureHashEmbeddingProvider(embedding.getDimensions());
    }

    private static String toolVectorKey(Tool tool) {
        return tool.name() + "\n" + tool.description();
    }

    private static String toolText(Tool tool) {
        return tool.name() + ". " + tool.description();
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
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
    private enum Strategy { LEXICAL, VECTOR, HYBRID }
    private record ScoredTool(Tool tool, double score, int schemaChars) {}
    private record ScoredParagraph(int index, String text, double score) {}
}
