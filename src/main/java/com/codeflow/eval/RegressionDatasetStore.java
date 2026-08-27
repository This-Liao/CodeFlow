package com.codeflow.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deduplicated JSONL regression set populated from classified failed traces. */
public final class RegressionDatasetStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path path;

    public RegressionDatasetStore(String workDir) {
        this.path = Path.of(workDir, ".codeflow", "evals", "regression.jsonl");
    }

    public synchronized boolean append(TraceRecord trace) {
        if (trace.getFailureTypes().isEmpty() || trace.getPrompt() == null || trace.getPrompt().isBlank()) return false;
        String id = hash(trace.getPrompt());
        if (contains(id)) return false;
        var item = new LinkedHashMap<String, Object>();
        item.put("id", id);
        item.put("prompt", trace.getPrompt());
        item.put("failureTypes", trace.getFailureTypes());
        item.put("sourceTrace", trace.getRunId());
        item.put("expectations", Map.of("noFailureTypes", trace.getFailureTypes()));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, MAPPER.writeValueAsString(item) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot append regression case", e);
        }
    }

    public Path path() { return path; }

    private boolean contains(String id) {
        if (!Files.isRegularFile(path)) return false;
        try (var lines = Files.lines(path)) {
            return lines.anyMatch(line -> line.contains("\"id\":\"" + id + "\""));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read regression dataset", e);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
