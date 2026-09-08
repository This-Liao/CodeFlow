package com.codeflow.durable;

import com.codeflow.config.DurableStoreConfig;

/** Selects the durable backend without leaking credentials into YAML. */
public final class DurableRepositoryFactory {
    private DurableRepositoryFactory() { }

    public static DurableTaskRepository create(String workDir, DurableStoreConfig config) {
        if (config == null || config.getBackend() == null
                || "local".equalsIgnoreCase(config.getBackend())) {
            return new DurableTaskStore(workDir);
        }
        if (!"postgres".equalsIgnoreCase(config.getBackend())) {
            throw new IllegalArgumentException("unsupported durable backend: " + config.getBackend());
        }
        String url = requiredEnv(config.getJdbcUrlEnv(), "PostgreSQL JDBC URL");
        String username = optionalEnv(config.getUsernameEnv());
        String password = optionalEnv(config.getPasswordEnv());
        return new PostgresDurableTaskRepository(url, username, password,
                config.getSchema(), config.getLeaseSeconds());
    }

    private static String requiredEnv(String name, String description) {
        String value = optionalEnv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(description + " environment variable is missing: " + name);
        }
        return value;
    }

    private static String optionalEnv(String name) {
        return name == null || name.isBlank() ? null : System.getenv(name);
    }
}
