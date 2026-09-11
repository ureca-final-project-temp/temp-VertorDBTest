package com.myapp.infrastructure.vector.milvus;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.port.VectorIndexManager;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class MilvusIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final MilvusProperties properties;

    public MilvusIndexManager(JsonHttpClient client, MilvusProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return "hnsw"; }

    @Override
    public void create() {
        List<Map<String, Object>> fields = List.of(
                field("id", "VarChar", true, Map.of("max_length", "256")),
                field("document_id", "VarChar", false, Map.of("max_length", "256")),
                field("chunk_id", "VarChar", false, Map.of("max_length", "256")),
                field("content", "VarChar", false, Map.of("max_length", "65535")),
                field("metadata", "JSON", false, Map.of()),
                field("embedding", "FloatVector", false, Map.of("dim", Integer.toString(properties.getDimension())))
        );
        Map<String, Object> body = Map.of(
                "dbName", properties.getDatabase(),
                "collectionName", properties.getCollection(),
                "schema", Map.of("autoId", false, "enabledDynamicField", false, "fields", fields),
                "indexParams", List.of(Map.of(
                        "metricType", metric(), "fieldName", "embedding", "indexName", "embedding_hnsw",
                        "indexType", "HNSW", "params", Map.of("M", properties.getHnswM(), "efConstruction", properties.getEfConstruction())))
        );
        requireSuccess(client.post("/v2/vectordb/collections/create", body));
    }

    @Override
    public void drop() {
        JsonNode response = client.post("/v2/vectordb/collections/list", Map.of("dbName", properties.getDatabase()));
        requireSuccess(response);
        JsonNode data = response.get("data");
        boolean exists = false;
        if (data != null && data.isArray()) {
            for (JsonNode collection : data) {
                if (properties.getCollection().equals(collection.asString())) {
                    exists = true;
                    break;
                }
            }
        }
        if (exists) requireSuccess(client.post("/v2/vectordb/collections/drop", identity()));
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        // Inserts first land in growing segments. Flush seals them so Milvus can build
        // the HNSW index instead of serving the benchmark from an exact growing segment.
        requireSuccess(client.post("/v2/vectordb/collections/flush", identity()));

        long deadline = System.nanoTime() + timeout.toNanos();
        Map<String, Object> request = Map.of(
                "dbName", properties.getDatabase(),
                "collectionName", properties.getCollection(),
                "indexName", "embedding_hnsw"
        );
        while (System.nanoTime() < deadline) {
            JsonNode response = client.post("/v2/vectordb/indexes/describe", request);
            requireSuccess(response);
            JsonNode indexes = response.get("data");
            if (indexes != null && indexes.isArray() && !indexes.isEmpty()) {
                JsonNode index = indexes.get(0);
                String state = text(index, "indexState");
                long indexedRows = number(index, "indexedRows");
                long pendingRows = number(index, "pendingRows");
                if ("Finished".equalsIgnoreCase(state)
                        && indexedRows >= expectedVectorCount
                        && pendingRows == 0) {
                    return;
                }
                String failure = text(index, "failReason");
                if (!failure.isBlank()) throw new IllegalStateException("Milvus index build failed: " + failure);
            }
            sleepBeforeRetry();
        }
        throw new IllegalStateException("Milvus HNSW index did not become ready within " + timeout);
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "M", properties.getHnswM(),
                "efConstruction", properties.getEfConstruction(),
                "metricType", metric(),
                "dimension", properties.getDimension()
        );
    }

    private Map<String, Object> identity() {
        return Map.of("dbName", properties.getDatabase(), "collectionName", properties.getCollection());
    }

    private Map<String, Object> field(String name, String type, boolean primary, Map<String, Object> params) {
        return Map.of("fieldName", name, "dataType", type, "isPrimary", primary, "elementTypeParams", params);
    }

    private String metric() {
        return switch (properties.getMetric()) {
            case COSINE -> "COSINE";
            case DOT -> "IP";
            case EUCLIDEAN -> "L2";
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asString();
    }

    private long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? 0 : value.asLong();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Milvus indexing", exception);
        }
    }

    static void requireSuccess(JsonNode response) {
        JsonNode code = response.get("code");
        if (code != null && code.asInt() != 0) throw new IllegalStateException("Milvus request failed: " + response);
    }
}
