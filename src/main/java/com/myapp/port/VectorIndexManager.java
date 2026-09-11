package com.myapp.port;

import java.util.Map;
import java.time.Duration;

public interface VectorIndexManager {
    String indexType();
    void create();
    void drop();

    default void rebuild() {
        drop();
        create();
    }

    default long indexSizeBytes() {
        return -1L;
    }

    default Map<String, Object> indexParameters() {
        return Map.of();
    }

    default void configureSearch(Map<String, Object> searchParameters) {
        // Most stores accept search parameters per request. Stores with index-level settings override this.
    }

    default void awaitReady(long expectedVectorCount, Duration timeout) {
        // Synchronous stores need no additional readiness barrier.
    }
}
