package com.myapp.infrastructure.vector.opensearch;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public class OpenSearchIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final OpenSearchProperties properties;

    public OpenSearchIndexManager(JsonHttpClient client, OpenSearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return "hnsw"; }

    @Override
    public void create() {
        Map<String, Object> vector = Map.of(
                "type", "knn_vector",
                "dimension", properties.getDimension(),
                "method", Map.of(
                        "name", "hnsw", "engine", "lucene", "space_type", spaceType(),
                        "parameters", Map.of("m", properties.getHnswM(), "ef_construction", properties.getEfConstruction())));
        client.put("/" + properties.getIndex(), Map.of(
                "settings", Map.of("index", Map.of("knn", true)),
                "mappings", Map.of("properties", Map.of(
                        "id", Map.of("type", "keyword"),
                        "documentId", Map.of("type", "keyword"),
                        "chunkId", Map.of("type", "keyword"),
                        "content", Map.of("type", "text", "index", false),
                        "metadata", Map.of("type", "object", "dynamic", true),
                        "embedding", vector))));
    }

    @Override
    public void drop() {
        try {
            client.delete("/" + properties.getIndex());
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    @Override
    public long indexSizeBytes() {
        JsonNode response = client.get("/" + properties.getIndex() + "/_stats/store");
        JsonNode indices = response.get("indices");
        JsonNode index = indices == null ? null : indices.get(properties.getIndex());
        JsonNode size = index == null || index.get("total") == null || index.get("total").get("store") == null
                ? null : index.get("total").get("store").get("size_in_bytes");
        return size == null ? -1 : size.asLong();
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "engine", "lucene",
                "m", properties.getHnswM(),
                "ef_construction", properties.getEfConstruction(),
                "space_type", spaceType(),
                "dimension", properties.getDimension()
        );
    }

    private String spaceType() {
        return switch (properties.getMetric()) {
            case COSINE -> "cosinesimil";
            case DOT -> "innerproduct";
            case EUCLIDEAN -> "l2";
        };
    }
}
