package com.myapp.benchmark;

import com.myapp.domain.vector.VectorSearchResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecallCalculator {
    public double recallAtK(List<VectorSearchResult> exact, List<VectorSearchResult> approximate, int k) {
        int denominator = Math.min(k, exact.size());
        if (denominator == 0) return 1.0;
        Set<String> expectedIds = new HashSet<>();
        exact.stream().limit(k).map(VectorSearchResult::id).forEach(expectedIds::add);
        long matches = approximate.stream().limit(k).map(VectorSearchResult::id).filter(expectedIds::contains).distinct().count();
        return (double) matches / denominator;
    }
}
