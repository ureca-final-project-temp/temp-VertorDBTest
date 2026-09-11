package com.myapp.benchmark;

import java.time.Instant;
import java.util.Map;

public record BenchmarkResult(
        String database,
        String indexType,
        double targetRecall,
        double actualRecall,
        Double comparisonRecall,
        double recallTolerance,
        boolean targetMet,
        String recallSelection,
        Double tuningRecall,
        double averageLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double qps,
        QuerySegment filtered,
        QuerySegment unfiltered,
        double averageCpuPercent,
        long peakMemoryBytes,
        long diskWriteBytes,
        long indexSizeBytes,
        long indexBuildTimeMs,
        long upsertTimeMs,
        int vectorCount,
        int queryExecutions,
        int concurrency,
        int topK,
        int warmupIterations,
        int measurementIterations,
        Map<String, Object> indexParameters,
        Map<String, Object> searchParameters,
        Map<String, Object> environment,
        Instant measuredAt
) {
    public BenchmarkResult {
        // Result files written before the segment split deserialize these as null.
        filtered = filtered == null ? QuerySegment.EMPTY : filtered;
        unfiltered = unfiltered == null ? QuerySegment.EMPTY : unfiltered;
        comparisonRecall = comparisonRecall == null
                ? (unfiltered.recall() == null ? actualRecall : unfiltered.recall())
                : comparisonRecall;
        if (!Double.isFinite(comparisonRecall)) {
            throw new IllegalArgumentException("comparisonRecall must be finite");
        }
        indexParameters = indexParameters == null ? Map.of() : Map.copyOf(indexParameters);
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
