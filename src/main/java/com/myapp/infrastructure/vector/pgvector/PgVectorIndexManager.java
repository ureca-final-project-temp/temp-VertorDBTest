package com.myapp.infrastructure.vector.pgvector;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.port.VectorIndexManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

public class PgVectorIndexManager implements VectorIndexManager {
    private final JdbcTemplate jdbcTemplate;
    private final PgVectorProperties properties;

    public PgVectorIndexManager(JdbcTemplate jdbcTemplate, PgVectorProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public String indexType() {
        return properties.getIndexType();
    }

    @Override
    public String engine() {
        return "PostgreSQL";
    }

    @Override
    public String searchParameterName() {
        return indexType().equals("ivfflat") ? "probes" : "ef_search";
    }

    @Override
    public int minimumSearchParameter(int topK) {
        return indexType().equals("ivfflat") ? 1 : topK;
    }

    @Override
    public int maximumSearchParameter() {
        return indexType().equals("ivfflat") ? properties.getIvfLists() : Integer.MAX_VALUE;
    }

    @Override
    public void create() {
        String table = properties.getTable();
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding vector(%d) NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.formatted(table, properties.getDimension()));
        if (indexType().equals("hnsw")) {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName() + " ON " + table
                    + " USING hnsw (embedding " + operatorClass() + ") WITH (m = " + properties.getHnswM()
                    + ", ef_construction = " + properties.getEfConstruction() + ")");
        } else {
            if (properties.getIvfLists() < 1) throw new IllegalStateException("ivf-lists must be positive");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName() + " ON " + table
                    + " USING ivfflat (embedding " + operatorClass() + ") WITH (lists = " + properties.getIvfLists() + ")");
        }
    }

    @Override
    public void drop() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + properties.getTable());
    }

    @Override
    public long indexSizeBytes() {
        Long bytes = jdbcTemplate.queryForObject(
                "SELECT COALESCE(pg_relation_size(?), 0)", Long.class, indexName());
        return bytes == null ? -1 : bytes;
    }

    @Override
    public Map<String, Object> indexParameters() {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        if (indexType().equals("hnsw")) {
            parameters.put("m", properties.getHnswM());
            parameters.put("ef_construction", properties.getEfConstruction());
        } else {
            parameters.put("lists", properties.getIvfLists());
        }
        parameters.put("metric", properties.getMetric().name());
        parameters.put("dimension", properties.getDimension());
        parameters.put("force_index_scan", properties.isForceIndexScan());
        return Map.copyOf(parameters);
    }

    private String indexName() {
        return properties.getTable() + "_" + indexType() + "_idx";
    }

    private String operatorClass() {
        return switch (properties.getMetric()) {
            case COSINE -> "vector_cosine_ops";
            case DOT -> "vector_ip_ops";
            case EUCLIDEAN -> "vector_l2_ops";
        };
    }
}
