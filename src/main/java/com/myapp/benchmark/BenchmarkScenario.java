package com.myapp.benchmark;

import java.util.Map;

public record BenchmarkScenario(
        String database,
        String indexType,
        double targetRecall,
        int topK,
        int concurrency,
        int warmupIterations,
        int measurementIterations,
        Map<String, Object> searchParameters
) {
    public BenchmarkScenario {
        if (targetRecall <= 0 || targetRecall > 1) throw new IllegalArgumentException("targetRecall must be in (0, 1]");
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be positive");
        if (warmupIterations < 0) throw new IllegalArgumentException("warmupIterations must not be negative");
        if (measurementIterations < 1) throw new IllegalArgumentException("measurementIterations must be positive");
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
    }
}
