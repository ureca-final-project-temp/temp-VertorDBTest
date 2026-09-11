package com.myapp.infrastructure.vector.qdrant;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;

import java.util.Map;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

public class QdrantIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final QdrantProperties properties;

    public QdrantIndexManager(JsonHttpClient client, QdrantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return "hnsw"; }

    @Override
    public void create() {
        client.put("/collections/" + properties.getCollection(), Map.of(
                "vectors", Map.of("size", properties.getDimension(), "distance", distance()),
                "hnsw_config", Map.of("m", properties.getHnswM(), "ef_construct", properties.getEfConstruction(),
                        "full_scan_threshold", properties.getFullScanThreshold()),
                "optimizers_config", Map.of("indexing_threshold", properties.getIndexingThreshold())
        ));
    }

    @Override
    public void drop() {
        try {
            client.delete("/collections/" + properties.getCollection());
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        long estimatedVectorBytes = expectedVectorCount * (long) properties.getDimension() * Float.BYTES;
        long fullScanThresholdBytes = properties.getFullScanThreshold() * 1024L;
        if (estimatedVectorBytes < fullScanThresholdBytes) {
            // Qdrant deliberately uses exact scan below this collection-size threshold.
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = client.get("/collections/" + properties.getCollection());
            JsonNode result = response.get("result");
            long indexed = result == null || result.get("indexed_vectors_count") == null ? 0 : result.get("indexed_vectors_count").asLong();
            String status = result == null || result.get("status") == null ? "" : result.get("status").asString();
            if (indexed >= expectedVectorCount && "green".equalsIgnoreCase(status)) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Qdrant indexing", exception);
            }
        }
        throw new IllegalStateException("Qdrant HNSW index did not become ready within " + timeout);
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "m", properties.getHnswM(),
                "ef_construct", properties.getEfConstruction(),
                "full_scan_threshold_kb", properties.getFullScanThreshold(),
                "indexing_threshold_kb", properties.getIndexingThreshold(),
                "metric", properties.getMetric().name(),
                "dimension", properties.getDimension()
        );
    }

    private String distance() {
        return switch (properties.getMetric()) {
            case COSINE -> "Cosine";
            case DOT -> "Dot";
            case EUCLIDEAN -> "Euclid";
        };
    }
}
