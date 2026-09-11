package com.myapp.benchmark;

import java.util.List;

/**
 * Latency and recall for one slice of the query set. Filtered and unfiltered queries are
 * reported apart because they exercise different code paths in every product.
 * {@code recall} is null when the slice contains no query.
 */
public record QuerySegment(
        int queryExecutions,
        Double recall,
        double averageMs,
        double p50Ms,
        double p95Ms,
        double p99Ms
) {
    public static final QuerySegment EMPTY = new QuerySegment(0, null, 0, 0, 0, 0);

    static QuerySegment of(LatencyCollector.Statistics latency, List<Double> recalls) {
        if (latency.count() == 0) return EMPTY;
        Double recall = recalls.isEmpty()
                ? null
                : recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new QuerySegment(latency.count(), recall,
                latency.averageMs(), latency.p50Ms(), latency.p95Ms(), latency.p99Ms());
    }
}
