package com.codeflow.config;

/** Configuration for one discoverable remote A2A agent. */
public class A2aAgentConfig {
    private String name;
    private String cardUrl;
    private String authEnv;
    private int timeoutSeconds = 120;
    private int pollIntervalMs = 500;
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCardUrl() { return cardUrl; }
    public void setCardUrl(String cardUrl) { this.cardUrl = cardUrl; }
    public String getAuthEnv() { return authEnv; }
    public void setAuthEnv(String authEnv) { this.authEnv = authEnv; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
