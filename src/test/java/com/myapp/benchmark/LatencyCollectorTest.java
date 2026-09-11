package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyCollectorTest {
    @Test
    void calculatesNearestRankPercentilesInMilliseconds() {
        LatencyCollector collector = new LatencyCollector();
        for (int millis = 1; millis <= 100; millis++) collector.record(millis * 1_000_000L);

        LatencyCollector.Statistics stats = collector.statistics();

        assertThat(stats.averageMs()).isEqualTo(50.5);
        assertThat(stats.p50Ms()).isEqualTo(50);
        assertThat(stats.p95Ms()).isEqualTo(95);
        assertThat(stats.p99Ms()).isEqualTo(99);
    }
}
