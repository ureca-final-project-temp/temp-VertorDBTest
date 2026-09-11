package com.myapp.infrastructure.vector.weaviate;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeaviateIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final WeaviateProperties properties;

    public WeaviateIndexManager(JsonHttpClient client, WeaviateProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return "hnsw"; }

    @Override
    public void create() {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
                property("externalId", "text"), property("documentId", "text"), property("chunkId", "text"),
                property("content", "text"), property("metadataJson", "text")
        ));
        properties.getFilterFields().forEach((name, type) -> fields.add(property(name, type)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("class", properties.getClassName());
        body.put("vectorizer", "none");
        body.put("vectorIndexType", "hnsw");
        body.put("vectorIndexConfig", Map.of(
                "distance", distance(), "maxConnections", properties.getHnswM(),
                "efConstruction", properties.getEfConstruction(), "ef", properties.getEf()));
        body.put("properties", fields);
        client.post("/v1/schema", body);
    }

    @Override
    public void drop() {
        try {
            client.delete("/v1/schema/" + properties.getClassName());
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    @Override
    public void configureSearch(Map<String, Object> searchParameters) {
        Object requested = searchParameters.get("ef");
        if (requested == null) return;
        int ef = requested instanceof Number number ? number.intValue() : Integer.parseInt(requested.toString());
        if (ef < 1) throw new IllegalArgumentException("ef must be positive");
        JsonNode current = client.get("/v1/schema/" + properties.getClassName());
        JsonNode vectorIndexConfig = current.get("vectorIndexConfig");
        if (!(current instanceof ObjectNode) || !(vectorIndexConfig instanceof ObjectNode config)) {
            throw new IllegalStateException("Weaviate returned an invalid class schema");
        }
        config.put("ef", ef);
        client.put("/v1/schema/" + properties.getClassName(), current);
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "maxConnections", properties.getHnswM(),
                "efConstruction", properties.getEfConstruction(),
                "distance", distance(),
                "dimension", properties.getDimension()
        );
    }

    private Map<String, Object> property(String name, String type) {
        return Map.of("name", name, "dataType", List.of(type));
    }

    private String distance() {
        return switch (properties.getMetric()) {
            case COSINE -> "cosine";
            case DOT -> "dot";
            case EUCLIDEAN -> "l2-squared";
        };
    }
}
