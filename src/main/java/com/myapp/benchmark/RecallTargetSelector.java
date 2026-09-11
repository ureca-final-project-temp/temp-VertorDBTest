package com.myapp.benchmark;

import java.util.Comparator;
import java.util.List;

/** Selects the cheapest in-range candidate, or the closest measured recall when no candidate is in range. */
final class RecallTargetSelector {
    private static final double EPSILON = 1e-12;

    private RecallTargetSelector() {
    }

    static <T> Selection<T> select(List<Candidate<T>> candidates, double target, double tolerance) {
        if (candidates == null || candidates.isEmpty()) throw new IllegalArgumentException("candidates must not be empty");
        if (!Double.isFinite(target) || target <= 0 || target > 1) {
            throw new IllegalArgumentException("target must be in (0, 1]");
        }
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 1) {
            throw new IllegalArgumentException("tolerance must be in [0, 1]");
        }
        Candidate<T> selected = candidates.stream()
                .filter(candidate -> withinTolerance(candidate.recall(), target, tolerance))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .min(Comparator.comparingDouble(candidate -> Math.abs(candidate.recall() - target)))
                        .orElseThrow());
        Strategy strategy = withinTolerance(selected.recall(), target, tolerance)
                ? Strategy.WITHIN_TOLERANCE
                : Strategy.CLOSEST_AVAILABLE;
        return new Selection<>(selected.value(), selected.recall(), strategy);
    }

    static boolean withinTolerance(double actual, double target, double tolerance) {
        return Double.isFinite(actual) && Math.abs(actual - target) <= tolerance + EPSILON;
    }

    record Candidate<T>(T value, double recall) {
        Candidate {
            if (!Double.isFinite(recall) || recall < 0 || recall > 1) {
                throw new IllegalArgumentException("recall must be in [0, 1]");
            }
        }
    }

    record Selection<T>(T value, double recall, Strategy strategy) {
    }

    enum Strategy {
        WITHIN_TOLERANCE,
        CLOSEST_AVAILABLE
    }
}
