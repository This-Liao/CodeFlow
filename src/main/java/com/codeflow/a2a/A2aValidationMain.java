package com.codeflow.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeflow.config.A2aAgentConfig;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reproducible Java-to-Python A2A engineering validation entrypoint. */
public final class A2aValidationMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SUMMARY = Pattern.compile(
            "Scanned \\*\\*(\\d+)\\*\\* Java files\\. Found \\*\\*(\\d+)\\*\\* candidate issues");

    private A2aValidationMain() {}

    public static void main(String[] args) throws Exception {
        String cardUrl = args.length > 0 ? args[0]
                : "http://127.0.0.1:8001/.well-known/agent-card.json";
        String prompt = args.length > 1 ? args[1]
                : "Analyze the CodeFlow Java sources for reliability risks.";

        A2aAgentConfig config = new A2aAgentConfig();
        config.setName("engineering-validation");
        config.setCardUrl(cardUrl);
        config.setTimeoutSeconds(120);
        config.setPollIntervalMs(100);
        A2aClient client = new A2aClient(config);

        long started = System.nanoTime();
        System.out.println("$ java -cp build/libs/codeflow.jar com.codeflow.a2a.A2aValidationMain");
        System.out.println();
        System.out.println("[1/4] Discovering remote Agent Card...");
        A2aAgentCard card = client.discover();
        A2aAgentCard.Interface endpoint = card.supportedInterfaces().getFirst();
        System.out.printf("      agent    : %s%n", card.name());
        System.out.printf("      protocol : A2A %s / %s%n", endpoint.protocolVersion(), endpoint.protocolBinding());
        System.out.printf("      skills   : %d%n", card.skills().size());

        System.out.println("[2/4] Sending repository static-analysis task...");
        A2aTaskResult result = client.delegate(prompt);
        System.out.printf("      task     : %s%n", result.taskId());
        System.out.printf("      state    : %s%n", result.state());

        if (!"TASK_STATE_COMPLETED".equals(result.state())) {
            throw new IllegalStateException("A2A task did not complete: " + result.state() + " " + result.message());
        }
        if (result.artifacts().isEmpty()) {
            throw new IllegalStateException("A2A task completed without artifacts");
        }

        int scannedFiles = -1;
        int findings = -1;
        for (A2aTaskResult.Artifact artifact : result.artifacts()) {
            for (A2aTaskResult.Part part : artifact.parts()) {
                if (part.text() == null) continue;
                Matcher matcher = SUMMARY.matcher(part.text());
                if (matcher.find()) {
                    scannedFiles = Integer.parseInt(matcher.group(1));
                    findings = Integer.parseInt(matcher.group(2));
                }
            }
        }

        System.out.println("[3/4] Verifying returned artifacts...");
        System.out.printf("      artifacts: %d%n", result.artifacts().size());
        System.out.printf("      java files: %d%n", scannedFiles);
        System.out.printf("      findings  : %d%n", findings);
        if (scannedFiles < 1 || findings < 0) {
            throw new IllegalStateException("Artifact summary is incomplete");
        }

        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("[4/4] Engineering validation PASSED");
        System.out.printf("      duration  : %d ms%n", durationMs);

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("agent", card.name());
        summary.put("protocolVersion", endpoint.protocolVersion());
        summary.put("protocolBinding", endpoint.protocolBinding());
        summary.put("taskId", result.taskId());
        summary.put("state", result.state());
        summary.put("artifacts", result.artifacts().size());
        summary.put("scannedJavaFiles", scannedFiles);
        summary.put("findings", findings);
        summary.put("durationMs", durationMs);
        System.out.println("CODEFLOW_VALIDATION_JSON=" + MAPPER.writeValueAsString(summary));
    }
}
