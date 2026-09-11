package com.myapp.domain.vector;

import java.util.Map;

public record VectorFilter(Map<String, Object> equals) {
    public static final VectorFilter NONE = new VectorFilter(Map.of());

    public VectorFilter {
        equals = equals == null ? Map.of() : Map.copyOf(equals);
    }

    public boolean isEmpty() {
        return equals.isEmpty();
    }
}
