package com.codeflow.durable;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real PostgreSQL test: set CODEFLOW_TEST_POSTGRES_URL to enable. */
class PostgresDurableTaskRepositoryTest {

    @Test
    void twoWorkersClaimDistinctTasksAndFencingRejectsStaleWriter() throws Exception {
        String url = System.getenv("CODEFLOW_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "CODEFLOW_TEST_POSTGRES_URL is not configured");
        String user = System.getenv("CODEFLOW_TEST_POSTGRES_USER");
        String password = System.getenv("CODEFLOW_TEST_POSTGRES_PASSWORD");
        String schema = "cf_test_" + UUID.randomUUID().toString().replace("-", "");

        try {
            var workerOne = new PostgresDurableTaskRepository(url, user, password, schema, 30);
            var workerTwo = new PostgresDurableTaskRepository(url, user, password, schema, 30);
            workerOne.create("task one", "s1", "producer", 3);
            workerOne.create("task two", "s2", "producer", 3);

            var first = workerOne.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
            var second = workerTwo.claimNext("worker-2", Duration.ofSeconds(30)).orElseThrow();
            assertNotEquals(first.taskId(), second.taskId(), "SKIP LOCKED must distribute work");

            DurableTaskRepository staleWriter = workerOne.fenced(first);
            staleWriter.checkpoint(first.taskId(), Map.of("step", 1), "owned by worker-1");
            assertTrue(workerOne.release(first.taskId(), first.workerId(), first.fencingToken()));

            var replacement = workerTwo.claim(first.taskId(), "worker-3", Duration.ofSeconds(30))
                    .orElseThrow();
            assertTrue(replacement.fencingToken() > first.fencingToken());
            assertThrows(IllegalStateException.class, () -> staleWriter.checkpoint(
                    first.taskId(), Map.of("step", 2), "stale process"));
            workerTwo.fenced(replacement).checkpoint(
                    replacement.taskId(), Map.of("step", 3), "new owner");
        } finally {
            Properties properties = new Properties();
            if (user != null && !user.isBlank()) properties.setProperty("user", user);
            if (password != null && !password.isBlank()) properties.setProperty("password", password);
            try (var connection = DriverManager.getConnection(url, properties);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }
}
