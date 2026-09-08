package com.codeflow.config;

/** Optional shared durable-task backend. Local JSON remains the default. */
public class DurableStoreConfig {
    private String backend = "local";
    private String jdbcUrlEnv = "CODEFLOW_POSTGRES_URL";
    private String usernameEnv = "CODEFLOW_POSTGRES_USER";
    private String passwordEnv = "CODEFLOW_POSTGRES_PASSWORD";
    private String schema = "codeflow";
    private int leaseSeconds = 60;

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getJdbcUrlEnv() { return jdbcUrlEnv; }
    public void setJdbcUrlEnv(String jdbcUrlEnv) { this.jdbcUrlEnv = jdbcUrlEnv; }
    public String getUsernameEnv() { return usernameEnv; }
    public void setUsernameEnv(String usernameEnv) { this.usernameEnv = usernameEnv; }
    public String getPasswordEnv() { return passwordEnv; }
    public void setPasswordEnv(String passwordEnv) { this.passwordEnv = passwordEnv; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
}
