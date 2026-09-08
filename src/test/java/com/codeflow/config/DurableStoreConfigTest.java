package com.codeflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DurableStoreConfigTest {
    @TempDir Path dir;

    @Test
    void loadsPostgresBackendWithoutPuttingCredentialsInYaml() throws Exception {
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, """
                providers:
                  - name: test
                    protocol: openai-compat
                    base_url: https://example.invalid/v1
                    model: test-model
                durable_store:
                  backend: postgres
                  jdbc_url_env: MY_DATABASE_URL
                  username_env: MY_DATABASE_USER
                  password_env: MY_DATABASE_PASSWORD
                  schema: codeflow_test
                  lease_seconds: 45
                """);

        AppConfig loaded = ConfigLoader.load(config.toString());
        assertEquals("postgres", loaded.getDurableStore().getBackend());
        assertEquals("MY_DATABASE_URL", loaded.getDurableStore().getJdbcUrlEnv());
        assertEquals("codeflow_test", loaded.getDurableStore().getSchema());
        assertEquals(45, loaded.getDurableStore().getLeaseSeconds());
    }

    @Test
    void rejectsUnsafeSchema() throws Exception {
        Path config = dir.resolve("unsafe.yaml");
        Files.writeString(config, """
                providers:
                  - name: test
                    protocol: openai-compat
                    base_url: https://example.invalid/v1
                    model: test-model
                durable_store:
                  backend: postgres
                  schema: codeflow;drop schema public
                """);
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(config.toString()));
    }
}
