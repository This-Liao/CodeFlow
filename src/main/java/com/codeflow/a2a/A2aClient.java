package com.codeflow.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeflow.config.A2aAgentConfig;
import com.codeflow.observability.Telemetry;
import io.opentelemetry.api.trace.SpanKind;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A2A 1.0 client supporting Agent Card discovery and HTTP+JSON/JSON-RPC task execution. */
public final class A2aClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final A2aAgentConfig config;
    private final HttpClient http;

    public A2aClient(A2aAgentConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    A2aClient(A2aAgentConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public A2aAgentCard discover() throws IOException, InterruptedException {
        Telemetry.SpanScope span = Telemetry.startSpan("codeflow.a2a.discover", SpanKind.CLIENT);
        try {
            span.span().setAttribute("codeflow.a2a.agent", config.getName());
            return discoverInternal();
        } catch (IOException | InterruptedException failure) {
            span.fail(failure);
            throw failure;
        } catch (RuntimeException failure) {
            span.fail(failure);
            throw failure;
        } finally {
            span.close();
        }
    }

    private A2aAgentCard discoverInternal() throws IOException, InterruptedException {
        JsonNode root = send(HttpRequest.newBuilder(checkedHttpUri(config.getCardUrl())).GET()).body();
        String name = requiredText(root, "name");
        var interfaces = new ArrayList<A2aAgentCard.Interface>();
        for (JsonNode item : root.path("supportedInterfaces")) {
            URI url = checkedHttpUri(requiredText(item, "url"));
            String binding = requiredText(item, "protocolBinding").toUpperCase(Locale.ROOT);
            String protocolVersion = requiredText(item, "protocolVersion");
            interfaces.add(new A2aAgentCard.Interface(url, binding, protocolVersion));
        }
        if (interfaces.isEmpty()) throw new IOException("Agent Card has no supportedInterfaces");
        if (interfaces.stream().noneMatch(i -> i.protocolVersion().startsWith("1."))) {
            throw new IOException("Agent Card does not advertise A2A 1.x");
        }
        var skills = new ArrayList<A2aAgentCard.Skill>();
        for (JsonNode item : root.path("skills")) {
            var tags = new ArrayList<String>();
            item.path("tags").forEach(tag -> tags.add(tag.asText()));
            skills.add(new A2aAgentCard.Skill(item.path("id").asText(), item.path("name").asText(),
                    item.path("description").asText(), List.copyOf(tags)));
        }
        return new A2aAgentCard(name, root.path("description").asText(), root.path("version").asText(),
                List.copyOf(interfaces), List.copyOf(skills));
    }

    public A2aTaskResult delegate(String prompt) throws IOException, InterruptedException {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        Telemetry.SpanScope span = Telemetry.startSpan("codeflow.a2a.delegate", SpanKind.CLIENT);
        try {
            span.span().setAttribute("codeflow.a2a.agent", config.getName());
            A2aTaskResult result = delegateInternal(prompt);
            span.span().setAttribute("codeflow.a2a.task.state", result.state());
            if (result.taskId() != null) span.span().setAttribute("codeflow.a2a.task.id", result.taskId());
            return result;
        } catch (IOException | InterruptedException failure) {
            span.fail(failure);
            throw failure;
        } catch (RuntimeException failure) {
            span.fail(failure);
            throw failure;
        } finally {
            span.close();
        }
    }

    private A2aTaskResult delegateInternal(String prompt) throws IOException, InterruptedException {
        A2aAgentCard card = discover();
        A2aAgentCard.Interface selected = selectInterface(card);
        ObjectNode request = MAPPER.createObjectNode();
        ObjectNode message = request.putObject("message");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("role", "ROLE_USER");
        message.putArray("parts").addObject().put("text", prompt).put("mediaType", "text/plain");
        request.putObject("configuration").put("returnImmediately", true);

        JsonNode response = isJsonRpc(selected)
                ? jsonRpc(selected.url(), "SendMessage", request)
                : rest(selected.url(), "message:send", "POST", request);
        A2aTaskResult current = normalize(response);
        if (current.taskId() == null || current.terminal()) return current;

        long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())).toNanos();
        while (!current.terminal() && System.nanoTime() < deadline) {
            Thread.sleep(Math.max(50, config.getPollIntervalMs()));
            ObjectNode params = MAPPER.createObjectNode().put("id", current.taskId());
            response = isJsonRpc(selected)
                    ? jsonRpc(selected.url(), "GetTask", params)
                    : rest(selected.url(), "tasks/" + URLEncoder.encode(current.taskId(), StandardCharsets.UTF_8),
                            "GET", null);
            current = normalize(response);
        }
        if (!current.terminal()) throw new IOException("A2A task timed out: " + current.taskId());
        return current;
    }

    private A2aAgentCard.Interface selectInterface(A2aAgentCard card) throws IOException {
        return card.supportedInterfaces().stream()
                .filter(i -> i.protocolVersion().startsWith("1."))
                .filter(i -> "HTTP+JSON".equals(i.protocolBinding()) || "JSONRPC".equals(i.protocolBinding()))
                .min(Comparator.comparingInt(i -> "HTTP+JSON".equals(i.protocolBinding()) ? 0 : 1))
                .orElseThrow(() -> new IOException("No supported A2A HTTP+JSON or JSONRPC 1.x interface"));
    }

    private JsonNode jsonRpc(URI base, String method, JsonNode params) throws IOException, InterruptedException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        request.set("params", params);
        JsonNode root = send(jsonRequest(base, request)).body();
        if (root.has("error")) throw new IOException("A2A JSON-RPC error: " + root.path("error"));
        return root.path("result");
    }

    private JsonNode rest(URI base, String suffix, String method, JsonNode body)
            throws IOException, InterruptedException {
        URI endpoint = appendPath(base, suffix);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Accept", "application/a2a+json, application/json")
                .header("A2A-Version", "1.0");
        if ("GET".equals(method)) builder.GET(); else builder = jsonRequest(builder, body);
        JsonNode root = send(builder).body();
        return root.has("task") || root.has("message") || root.has("id") || root.has("role")
                ? root : root.path("result");
    }

    private Response send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        String token = config.getAuthEnv() == null ? null : System.getenv(config.getAuthEnv());
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        Telemetry.inject(builder);
        HttpResponse<byte[]> response = http.send(builder.timeout(Duration.ofSeconds(Math.max(1,
                config.getTimeoutSeconds()))).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) throw new IOException("A2A response exceeds 4 MiB limit");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("A2A HTTP " + response.statusCode() + ": "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return new Response(MAPPER.readTree(response.body()));
    }

    private HttpRequest.Builder jsonRequest(URI uri, JsonNode body) {
        return jsonRequest(HttpRequest.newBuilder(uri), body);
    }

    private HttpRequest.Builder jsonRequest(HttpRequest.Builder builder, JsonNode body) {
        return builder.header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    }

    private A2aTaskResult normalize(JsonNode input) throws IOException {
        JsonNode root = input;
        if (root.has("task")) root = root.path("task");
        if (root.has("message") && !root.has("status") && !root.has("id")) {
            JsonNode message = root.path("message");
            return new A2aTaskResult(null, message.path("contextId").asText(null), "MESSAGE",
                    textFromMessage(message), List.of(), input);
        }
        if (root.has("role") && root.has("parts")) {
            return new A2aTaskResult(null, root.path("contextId").asText(null), "MESSAGE",
                    textFromMessage(root), List.of(), input);
        }
        String taskId = root.path("id").asText(null);
        if (taskId == null) throw new IOException("A2A response is neither a Task nor a Message");
        String state = root.path("status").path("state").asText("TASK_STATE_UNSPECIFIED");
        String message = textFromMessage(root.path("status").path("message"));
        var artifacts = new ArrayList<A2aTaskResult.Artifact>();
        for (JsonNode artifact : root.path("artifacts")) {
            var parts = new ArrayList<A2aTaskResult.Part>();
            for (JsonNode part : artifact.path("parts")) {
                parts.add(new A2aTaskResult.Part(part.path("text").asText(null), part.path("url").asText(null),
                        part.get("data"), part.path("mediaType").asText(null), part.path("filename").asText(null)));
            }
            artifacts.add(new A2aTaskResult.Artifact(artifact.path("artifactId").asText(),
                    artifact.path("name").asText(), artifact.path("description").asText(), List.copyOf(parts)));
        }
        return new A2aTaskResult(taskId, root.path("contextId").asText(null), state, message,
                List.copyOf(artifacts), input);
    }

    private static String textFromMessage(JsonNode message) {
        var parts = new ArrayList<String>();
        message.path("parts").forEach(part -> {
            if (part.has("text")) parts.add(part.path("text").asText());
        });
        return String.join("\n", parts);
    }

    private static boolean isJsonRpc(A2aAgentCard.Interface selected) {
        return "JSONRPC".equals(selected.protocolBinding());
    }

    private static URI checkedHttpUri(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("A2A URI must use HTTP(S)");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid A2A URI", e);
        }
    }

    private static URI appendPath(URI base, String suffix) {
        String value = base.toString();
        if (!value.endsWith("/")) value += "/";
        return URI.create(value + suffix);
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IOException("Agent Card is missing " + field);
        return value;
    }

    private record Response(JsonNode body) {}
}
