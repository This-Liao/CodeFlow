package com.mewcode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Computes success/token/latency/tool-error metrics and an optional regression gate. */
public final class EvalReportGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportGenerator() {}

    public static EvalReport generate(Path tracesDir, Path baselineReport) {
        var traces = readTraces(tracesDir);
        int runs = traces.size();
        long successes = traces.stream().filter(TraceRecord::isSuccess).count();
        double successRate = runs == 0 ? 0 : (double) successes / runs;
        double avgInput = traces.stream().mapToInt(TraceRecord::getInputTokens).average().orElse(0);
        double avgOutput = traces.stream().mapToInt(TraceRecord::getOutputTokens).average().orElse(0);
        var durations = traces.stream().map(TraceRecord::getDurationMs).sorted().toList();
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);
        long toolCalls = traces.stream().mapToLong(trace -> trace.getToolResults().size()).sum();
        long toolErrors = traces.stream().flatMap(trace -> trace.getToolResults().stream())
                .filter(result -> Boolean.TRUE.equals(result.get("isError"))).count();
        double toolErrorRate = toolCalls == 0 ? 0 : (double) toolErrors / toolCalls;
        var failures = new LinkedHashMap<String, Integer>();
        traces.stream().flatMap(trace -> trace.getFailureTypes().stream())
                .forEach(type -> failures.merge(type.name(), 1, Integer::sum));

        var regressions = new ArrayList<String>();
        EvalReport candidate = new EvalReport(runs, successRate, avgInput, avgOutput, p50, p95,
                toolErrorRate, failures, List.of());
        if (baselineReport != null && Files.isRegularFile(baselineReport)) {
            try {
                EvalReport baseline = MAPPER.readValue(baselineReport.toFile(), EvalReport.class);
                if (candidate.successRate() + 1e-9 < baseline.successRate()) {
                    regressions.add("success rate dropped from %.2f%% to %.2f%%".formatted(
                            baseline.successRate() * 100, candidate.successRate() * 100));
                }
                if (baseline.latencyP95Ms() > 0 && candidate.latencyP95Ms() > baseline.latencyP95Ms() * 1.20) {
                    regressions.add("p95 latency increased by more than 20%% (%dms -> %dms)".formatted(
                            baseline.latencyP95Ms(), candidate.latencyP95Ms()));
                }
                if (candidate.toolErrorRate() > baseline.toolErrorRate() + 0.02) {
                    regressions.add("tool error rate increased by more than 2pp (%.2f%% -> %.2f%%)".formatted(
                            baseline.toolErrorRate() * 100, candidate.toolErrorRate() * 100));
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read baseline report: " + baselineReport, e);
            }
        }
        return new EvalReport(runs, successRate, avgInput, avgOutput, p50, p95,
                toolErrorRate, failures, List.copyOf(regressions));
    }

    public static Output write(Path tracesDir, Path baselineReport, Path outputDir) {
        EvalReport report = generate(tracesDir, baselineReport);
        try {
            Files.createDirectories(outputDir);
            Path json = outputDir.resolve("eval-report.json");
            Path markdown = outputDir.resolve("eval-report.md");
            MAPPER.writeValue(json.toFile(), report);
            Files.writeString(markdown, toMarkdown(report));
            return new Output(report, json, markdown);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write eval report", e);
        }
    }

    private static List<TraceRecord> readTraces(Path tracesDir) {
        if (!Files.isDirectory(tracesDir)) return List.of();
        var traces = new ArrayList<TraceRecord>();
        try (var files = Files.list(tracesDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try { traces.add(MAPPER.readValue(path.toFile(), TraceRecord.class)); }
                        catch (IOException e) { throw new IllegalStateException("Invalid trace: " + path, e); }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read traces: " + tracesDir, e);
        }
        return List.copyOf(traces);
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static String toMarkdown(EvalReport report) {
        var out = new StringBuilder("# CodeFlow Eval Report\n\n");
        out.append("| Metric | Value |\n|---|---:|\n");
        out.append("| Runs | ").append(report.runs()).append(" |\n");
        out.append("| Success rate | ").append(String.format(Locale.ROOT, "%.2f%%", report.successRate() * 100)).append(" |\n");
        out.append("| Average input tokens | ").append(String.format(Locale.ROOT, "%.0f", report.averageInputTokens())).append(" |\n");
        out.append("| Average output tokens | ").append(String.format(Locale.ROOT, "%.0f", report.averageOutputTokens())).append(" |\n");
        out.append("| Latency p50 | ").append(report.latencyP50Ms()).append(" ms |\n");
        out.append("| Latency p95 | ").append(report.latencyP95Ms()).append(" ms |\n");
        out.append("| Tool error rate | ").append(String.format(Locale.ROOT, "%.2f%%", report.toolErrorRate() * 100)).append(" |\n\n");
        out.append("## Failure classification\n\n");
        if (report.failureCounts().isEmpty()) out.append("No classified failures.\n");
        else report.failureCounts().forEach((name, count) -> out.append("- ").append(name).append(": ").append(count).append("\n"));
        out.append("\n## Regression gate\n\n");
        if (report.passedRegressionGate()) out.append("PASS — no configured metric regressed.\n");
        else report.regressions().forEach(item -> out.append("- FAIL: ").append(item).append("\n"));
        return out.toString();
    }

    public record Output(EvalReport report, Path json, Path markdown) {}
}
