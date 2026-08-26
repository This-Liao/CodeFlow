package com.mewcode.eval;

import java.util.List;
import java.util.Map;

/** Aggregate metrics and non-regression decision for a trace corpus. */
public record EvalReport(
        int runs,
        double successRate,
        double averageInputTokens,
        double averageOutputTokens,
        long latencyP50Ms,
        long latencyP95Ms,
        double toolErrorRate,
        Map<String, Integer> failureCounts,
        List<String> regressions
) {
    public boolean passedRegressionGate() { return regressions == null || regressions.isEmpty(); }
}
