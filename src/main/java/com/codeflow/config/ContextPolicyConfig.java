package com.codeflow.config;

/** Token and schema budgets for stage-aware context assembly. */
public class ContextPolicyConfig {
    private boolean enabled = true;
    private int maxToolSchemas = 24;
    private int maxSchemaChars = 30_000;
    private int maxMemoryChars = 12_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxToolSchemas() { return maxToolSchemas; }
    public void setMaxToolSchemas(int maxToolSchemas) { this.maxToolSchemas = maxToolSchemas; }
    public int getMaxSchemaChars() { return maxSchemaChars; }
    public void setMaxSchemaChars(int maxSchemaChars) { this.maxSchemaChars = maxSchemaChars; }
    public int getMaxMemoryChars() { return maxMemoryChars; }
    public void setMaxMemoryChars(int maxMemoryChars) { this.maxMemoryChars = maxMemoryChars; }
}
