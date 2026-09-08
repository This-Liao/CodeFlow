package com.codeflow.config;

/** Token and schema budgets for stage-aware context assembly. */
public class ContextPolicyConfig {
    private boolean enabled = true;
    private boolean includeDeferredTools;
    private int maxToolSchemas = 24;
    private int maxSchemaChars = 30_000;
    private int maxMemoryChars = 12_000;
    private String strategy = "hybrid";
    private double lexicalWeight = 0.65;
    private double vectorWeight = 0.35;
    private EmbeddingConfig embedding = new EmbeddingConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isIncludeDeferredTools() { return includeDeferredTools; }
    public void setIncludeDeferredTools(boolean includeDeferredTools) {
        this.includeDeferredTools = includeDeferredTools;
    }
    public int getMaxToolSchemas() { return maxToolSchemas; }
    public void setMaxToolSchemas(int maxToolSchemas) { this.maxToolSchemas = maxToolSchemas; }
    public int getMaxSchemaChars() { return maxSchemaChars; }
    public void setMaxSchemaChars(int maxSchemaChars) { this.maxSchemaChars = maxSchemaChars; }
    public int getMaxMemoryChars() { return maxMemoryChars; }
    public void setMaxMemoryChars(int maxMemoryChars) { this.maxMemoryChars = maxMemoryChars; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public double getLexicalWeight() { return lexicalWeight; }
    public void setLexicalWeight(double lexicalWeight) { this.lexicalWeight = lexicalWeight; }
    public double getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
    public EmbeddingConfig getEmbedding() { return embedding; }
    public void setEmbedding(EmbeddingConfig embedding) {
        this.embedding = embedding == null ? new EmbeddingConfig() : embedding;
    }

    public static class EmbeddingConfig {
        private boolean enabled = true;
        private String provider = "local";
        private String baseUrl = "";
        private String model = "";
        private String apiKey = "";
        private String apiKeyEnv = "OPENAI_API_KEY";
        private int timeoutSeconds = 10;
        private int dimensions = 384;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }
}
