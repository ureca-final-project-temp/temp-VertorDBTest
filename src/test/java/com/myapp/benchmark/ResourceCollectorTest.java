package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceCollectorTest {
    @Test
    void parsesDockerDecimalAndBinaryUnits() {
        assertThat(ResourceCollector.Measurement.parseBytes("12.5MB")).isEqualTo(12_500_000L);
        assertThat(ResourceCollector.Measurement.parseBytes("2MiB")).isEqualTo(2_097_152L);
        assertThat(ResourceCollector.Measurement.parseBytes("1.5GB")).isEqualTo(1_500_000_000L);
    }

    @Test
    void excludesIdleBaselineFromCpuAndUsesItForDiskDelta() {
        ResourceCollector.Snapshot baseline = new ResourceCollector.Snapshot(1, 100, 1_000);
        ResourceCollector.Usage usage = ResourceCollector.Measurement.summarize(baseline, List.of(
                new ResourceCollector.Snapshot(80, 200, 1_500),
                new ResourceCollector.Snapshot(120, 250, 2_200)));

        assertThat(usage.averageCpuPercent()).isEqualTo(100);
        assertThat(usage.peakMemoryBytes()).isEqualTo(250);
        assertThat(usage.diskWriteBytes()).isEqualTo(1_200);
    }
}
