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
        return "hnsw";
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
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw_idx ON " + table
                + " USING hnsw (embedding " + operatorClass() + ") WITH (m = " + properties.getHnswM()
                + ", ef_construction = " + properties.getEfConstruction() + ")");
    }

    @Override
    public void drop() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + properties.getTable());
    }

    @Override
    public long indexSizeBytes() {
        Long bytes = jdbcTemplate.queryForObject(
                "SELECT COALESCE(pg_relation_size(?), 0)", Long.class, properties.getTable() + "_hnsw_idx");
        return bytes == null ? -1 : bytes;
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "m", properties.getHnswM(),
                "ef_construction", properties.getEfConstruction(),
                "metric", properties.getMetric().name(),
                "dimension", properties.getDimension(),
                "force_index_scan", properties.isForceIndexScan()
        );
    }

    private String operatorClass() {
        return switch (properties.getMetric()) {
            case COSINE -> "vector_cosine_ops";
            case DOT -> "vector_ip_ops";
            case EUCLIDEAN -> "vector_l2_ops";
        };
    }
}
