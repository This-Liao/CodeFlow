package com.mewcode.a2a;

import com.mewcode.config.A2aAgentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class A2aClientTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger polls = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/.well-known/agent-card.json", exchange -> respond(exchange, 200, """
                {
                  "name":"Python Static Analysis Agent",
                  "description":"Cross-language test agent",
                  "version":"1.0.0",
                  "supportedInterfaces":[
                    {"url":"%s/a2a/v1","protocolBinding":"HTTP+JSON","protocolVersion":"1.0"}
                  ],
                  "skills":[{"id":"static-analysis","name":"Static analysis","description":"Finds risks","tags":["code"]}]
                }
                """.formatted(baseUrl)));
        server.createContext("/a2a/v1/message:send", exchange -> respond(exchange, 200, """
                {"task":{"id":"task-1","contextId":"ctx-1","status":{"state":"TASK_STATE_WORKING"},"artifacts":[]}}
                """));
        server.createContext("/a2a/v1/tasks/task-1", exchange -> {
            polls.incrementAndGet();
            respond(exchange, 200, """
                    {"id":"task-1","contextId":"ctx-1","status":{"state":"TASK_STATE_COMPLETED","message":{"parts":[{"text":"analysis complete"}]}},"artifacts":[{"artifactId":"a-1","name":"report","parts":[{"text":"no critical findings","mediaType":"text/plain"}]}]}
                    """);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void discoversCardAndPollsTaskUntilArtifact() throws Exception {
        var config = new A2aAgentConfig();
        config.setName("static-analysis");
        config.setCardUrl(baseUrl + "/.well-known/agent-card.json");
        config.setPollIntervalMs(50);
        config.setTimeoutSeconds(2);
        var client = new A2aClient(config);

        var card = client.discover();
        assertEquals("Python Static Analysis Agent", card.name());
        assertEquals("1.0", card.supportedInterfaces().getFirst().protocolVersion());

        var result = client.delegate("analyze the repository");
        assertEquals("TASK_STATE_COMPLETED", result.state());
        assertEquals("analysis complete", result.message());
        assertEquals("no critical findings", result.artifacts().getFirst().parts().getFirst().text());
        assertTrue(polls.get() >= 1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/a2a+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
