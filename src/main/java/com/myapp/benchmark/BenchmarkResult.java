package com.myapp.benchmark;

import java.time.Instant;
import java.util.Map;

public record BenchmarkResult(
        String database,
        String indexType,
        double targetRecall,
        double actualRecall,
        double averageLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double qps,
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
        indexParameters = indexParameters == null ? Map.of() : Map.copyOf(indexParameters);
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
