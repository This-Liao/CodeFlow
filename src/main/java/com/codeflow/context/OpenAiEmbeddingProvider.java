package com.codeflow.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeflow.config.ContextPolicyConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** OpenAI-compatible /embeddings client with bounded timeout and batch input. */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;

    public OpenAiEmbeddingProvider(ContextPolicyConfig.EmbeddingConfig config) {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().replaceAll("/+$", "");
        this.endpoint = base.endsWith("/embeddings") ? base : base + "/embeddings";
        this.model = config.getModel();
        String configured = config.getApiKey();
        String envName = config.getApiKeyEnv();
        String fromEnv = envName == null || envName.isBlank() ? "" : System.getenv(envName);
        this.apiKey = configured != null && !configured.isBlank() ? configured
                : fromEnv == null ? "" : fromEnv;
        this.timeoutSeconds = Math.max(1, config.getTimeoutSeconds());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public List<double[]> embed(List<String> texts) throws Exception {
        if (endpoint.isBlank() || model == null || model.isBlank()) {
            throw new IllegalStateException("embedding base_url and model are required");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        var input = body.putArray("input");
        texts.forEach(input::add);
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<byte[]> response = client.send(
                request.POST(HttpRequest.BodyPublishers.ofByteArray(
                        MAPPER.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("embedding HTTP " + response.statusCode());
        }
        JsonNode root = MAPPER.readTree(response.body());
        var rows = new ArrayList<Row>();
        for (JsonNode item : root.path("data")) {
            var values = new double[item.path("embedding").size()];
            for (int i = 0; i < values.length; i++) values[i] = item.path("embedding").get(i).asDouble();
            rows.add(new Row(item.path("index").asInt(rows.size()), values));
        }
        rows.sort(Comparator.comparingInt(Row::index));
        if (rows.size() != texts.size()) throw new IllegalStateException("embedding response size mismatch");
        return rows.stream().map(Row::vector).toList();
    }

    private record Row(int index, double[] vector) { }
}
