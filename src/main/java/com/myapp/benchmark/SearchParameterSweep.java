package com.myapp.benchmark;

import com.myapp.port.VectorIndexManager;

import java.util.List;
import java.util.Map;

/** Expands a grid without consulting Recall or choosing a quality target. */
final class SearchParameterSweep {
    private SearchParameterSweep() { }

    static List<Map<String, Object>> parameters(BenchmarkScenario scenario, VectorIndexManager manager,
                                                 List<Integer> defaults) {
        String key = manager.searchParameterName();
        if (!scenario.searchParameters().isEmpty()) {
            if (key != null && scenario.searchParameters().containsKey(key)) {
                Object value = scenario.searchParameters().get(key);
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.intValue()) {
                    throw new IllegalArgumentException(key + " must be an integer");
                }
                validate(number.intValue(), scenario, manager);
            }
            return List.of(scenario.searchParameters());
        }
        if (key == null || key.isBlank()) {
            if (!scenario.searchParameterValues().isEmpty()) {
                throw new IllegalArgumentException("This index has no sweep parameter");
            }
            return List.of(Map.of("k", scenario.topK()));
        }
        List<Integer> values = scenario.searchParameterValues().isEmpty() ? defaults : scenario.searchParameterValues();
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Search parameter grid must not be empty");
        for (Integer value : values) {
            if (value == null) throw new IllegalArgumentException("Search parameter grid must not contain null");
            validate(value, scenario, manager);
        }
        return values.stream().distinct().sorted().map(value -> Map.<String, Object>of(key, value)).toList();
    }

    private static void validate(int value, BenchmarkScenario scenario, VectorIndexManager manager) {
        int minimum = manager.minimumSearchParameter(scenario.topK());
        int maximum = manager.maximumSearchParameter();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(manager.searchParameterName() + "=" + value
                    + " is outside the supported parameter range " + minimum + ".." + maximum);
        }
    }
}
