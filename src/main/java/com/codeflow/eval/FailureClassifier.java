package com.codeflow.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic trace classifier; suitable for CI and regression-dataset generation. */
public final class FailureClassifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FailureClassifier() {}

    public static List<FailureType> classify(TraceRecord trace) {
        Set<FailureType> result = EnumSet.noneOf(FailureType.class);
        var signatures = new HashMap<String, Integer>();
        for (var call : trace.getToolCalls()) {
            try {
                String signature = call.get("tool") + ":" + MAPPER.writeValueAsString(call.get("args"));
                signatures.merge(signature, 1, Integer::sum);
            } catch (JsonProcessingException ignored) {}
        }
        if (signatures.values().stream().anyMatch(count -> count >= 3)) {
            result.add(FailureType.REPEATED_TOOL_CALL);
        }

        for (var item : trace.getToolResults()) {
            if (!Boolean.TRUE.equals(item.get("isError"))) continue;
            String tool = String.valueOf(item.getOrDefault("tool", ""));
            String output = String.valueOf(item.getOrDefault("output", "")).toLowerCase(Locale.ROOT);
            if (output.contains("unknown tool")) result.add(FailureType.TOOL_SELECTION_ERROR);
            else if (output.contains("required") || output.contains("invalid argument")
                    || output.contains("invalid params") || output.contains("missing field")) {
                result.add(FailureType.INVALID_TOOL_PARAMS);
            } else if ("Agent".equals(tool) || "A2ADelegate".equals(tool) || output.contains("subagent")) {
                result.add(FailureType.SUBAGENT_FAILURE);
            } else if ("Bash".equals(tool) && (output.contains("test") || output.contains("gradle")
                    || output.contains("maven") || output.contains("pytest"))) {
                result.add(FailureType.VERIFICATION_FAILURE);
            } else {
                result.add(FailureType.TOOL_EXECUTION_ERROR);
            }
        }

        if (trace.getRetries().stream().map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.contains("context") || s.contains("compact"))) {
            result.add(FailureType.CONTEXT_LOSS);
        }
        if (!trace.getErrors().isEmpty()) result.add(FailureType.MODEL_ERROR);
        return result.stream().toList();
    }
}
