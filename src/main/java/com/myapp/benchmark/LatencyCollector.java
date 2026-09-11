package com.myapp.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LatencyCollector {
    private final ConcurrentLinkedQueue<Long> nanoseconds = new ConcurrentLinkedQueue<>();

    public void record(long elapsedNanoseconds) {
        if (elapsedNanoseconds < 0) throw new IllegalArgumentException("elapsedNanoseconds must not be negative");
        nanoseconds.add(elapsedNanoseconds);
    }

    public Statistics statistics() {
        if (nanoseconds.isEmpty()) return new Statistics(0, 0, 0, 0);
        List<Long> sorted = new ArrayList<>(nanoseconds);
        sorted.sort(Long::compareTo);
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        return new Statistics(average, percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99));
    }

    private double percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    public record Statistics(double averageMs, double p50Ms, double p95Ms, double p99Ms) {
    }
}
