package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallTargetSelectorTest {
    @Test
    void selectsFirstCandidateInsideInclusiveTolerance() {
        var selection = RecallTargetSelector.select(List.of(
                new RecallTargetSelector.Candidate<>(10, 0.89),
                new RecallTargetSelector.Candidate<>(20, 0.90),
                new RecallTargetSelector.Candidate<>(40, 0.91)), 0.90, 0.01);

        assertThat(selection.value()).isEqualTo(10);
        assertThat(selection.strategy()).isEqualTo(RecallTargetSelector.Strategy.WITHIN_TOLERANCE);
    }

    @Test
    void selectsClosestActualRecallWhenNoCandidateIsInRange() {
        var selection = RecallTargetSelector.select(List.of(
                new RecallTargetSelector.Candidate<>(10, 0.72),
                new RecallTargetSelector.Candidate<>(20, 0.84),
                new RecallTargetSelector.Candidate<>(40, 0.93)), 0.80, 0.01);

        assertThat(selection.value()).isEqualTo(20);
        assertThat(selection.recall()).isEqualTo(0.84);
        assertThat(selection.strategy()).isEqualTo(RecallTargetSelector.Strategy.CLOSEST_AVAILABLE);
    }
}
