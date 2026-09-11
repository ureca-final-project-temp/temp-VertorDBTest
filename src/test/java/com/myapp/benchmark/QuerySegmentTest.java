package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QuerySegmentTest {
    @Test
    void averagesRecallOverTheSegment() {
        QuerySegment segment = QuerySegment.of(
                new LatencyCollector.Statistics(3, 10, 8, 20, 25), List.of(1.0, 0.8, 0.6));

        assertThat(segment.queryExecutions()).isEqualTo(3);
        assertThat(segment.recall()).isCloseTo(0.8, within(1e-9));
        assertThat(segment.p95Ms()).isEqualTo(20);
    }

    @Test
    void reportsEmptyWhenTheSegmentHasNoQuery() {
        assertThat(QuerySegment.of(LatencyCollector.Statistics.EMPTY, List.of())).isEqualTo(QuerySegment.EMPTY);
        assertThat(QuerySegment.EMPTY.recall()).isNull();
    }
}
