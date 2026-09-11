package com.myapp.dataset;

import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QuerySetLoader {
    private final ObjectMapper objectMapper;

    public QuerySetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BenchmarkQuery> load(Path definitionsPath, Path vectorsPath) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (JsonNode node : JsonlSupport.read(definitionsPath, objectMapper)) {
            String id = JsonlSupport.text(node, "queryId", "query_id", "id");
            if (id == null) throw new IllegalArgumentException("Query definition is missing queryId");
            Map<String, Object> filter = JsonlSupport.object(node, objectMapper, "filter");
            definitions.put(id, new Definition(JsonlSupport.text(node, "query", "text"), filter));
        }

        List<BenchmarkQuery> queries = JsonlSupport.readMapped(vectorsPath, objectMapper, node -> {
            String id = JsonlSupport.text(node, "queryId", "query_id", "id");
            Definition definition = definitions.getOrDefault(id, new Definition(JsonlSupport.text(node, "query", "text"), Map.of()));
            Map<String, Object> inlineFilter = JsonlSupport.object(node, objectMapper, "filter");
            Map<String, Object> filter = inlineFilter.isEmpty() ? definition.filter() : inlineFilter;
            return new BenchmarkQuery(id, definition.query(), JsonlSupport.vector(node, "embedding", "vector"), new VectorFilter(filter));
        });
        if (queries.isEmpty()) throw new IllegalStateException("Query vector dataset is empty: " + vectorsPath);
        return queries;
    }

    private record Definition(String query, Map<String, Object> filter) {
    }
}
