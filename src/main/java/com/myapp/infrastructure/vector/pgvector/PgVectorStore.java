package com.myapp.infrastructure.vector.pgvector;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.port.VectorStore;
import com.pgvector.PGvector;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PgVectorStore implements VectorStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PgVectorProperties properties;

    public PgVectorStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PgVectorProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String database() {
        return "pgvector";
    }

    @Override
    public int dimension() { return properties.getDimension(); }

    @Override
    public DistanceMetric metric() { return properties.getMetric(); }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) return;
        validateDimensions(documents);
        String sql = "INSERT INTO " + properties.getTable()
                + " (id, document_id, chunk_id, content, embedding, metadata) VALUES (?, ?, ?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (id) DO UPDATE SET document_id=EXCLUDED.document_id, chunk_id=EXCLUDED.chunk_id, "
                + "content=EXCLUDED.content, embedding=EXCLUDED.embedding, metadata=EXCLUDED.metadata";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                VectorDocument document = documents.get(index);
                statement.setString(1, document.id());
                statement.setString(2, document.documentId());
                statement.setString(3, document.chunkId());
                statement.setString(4, document.content());
                statement.setObject(5, new PGvector(document.embedding()));
                statement.setString(6, objectMapper.writeValueAsString(document.metadata()));
            }

            @Override
            public int getBatchSize() {
                return documents.size();
            }
        });
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimension()) throw new IllegalArgumentException("Unexpected query vector dimension");
        int efSearch = request.intParameter("ef_search", properties.getDefaultEfSearch());
        if (efSearch < 1) throw new IllegalArgumentException("ef_search must be positive");
        return jdbcTemplate.execute((ConnectionCallback<List<VectorSearchResult>>) connection -> {
            try (PreparedStatement setting = connection.prepareStatement(
                    "SELECT set_config('hnsw.ef_search', ?, false), set_config('enable_seqscan', ?, false)")) {
                setting.setString(1, Integer.toString(efSearch));
                setting.setString(2, properties.isForceIndexScan() ? "off" : "on");
                setting.execute();
            }
            String operator = operator();
            String score = properties.getMetric() == DistanceMetric.COSINE ? "1 - (embedding " + operator + " ?)" : "-(embedding " + operator + " ?)";
            StringBuilder sql = new StringBuilder("SELECT id, document_id, chunk_id, ").append(score)
                    .append(" AS score FROM ").append(properties.getTable()).append(" WHERE true");
            request.filter().equals().forEach((key, value) -> sql.append(" AND metadata ->> ? = ?"));
            sql.append(" ORDER BY embedding ").append(operator).append(" ? LIMIT ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                PGvector vector = new PGvector(request.queryVector());
                statement.setObject(parameter++, vector);
                for (Map.Entry<String, Object> entry : request.filter().equals().entrySet()) {
                    statement.setString(parameter++, entry.getKey());
                    statement.setString(parameter++, String.valueOf(entry.getValue()));
                }
                statement.setObject(parameter++, vector);
                statement.setInt(parameter, request.topK());
                try (var resultSet = statement.executeQuery()) {
                    List<VectorSearchResult> results = new ArrayList<>();
                    while (resultSet.next()) {
                        results.add(new VectorSearchResult(resultSet.getString("id"), resultSet.getString("document_id"),
                                resultSet.getString("chunk_id"), resultSet.getDouble("score")));
                    }
                    return results;
                }
            }
        });
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("TRUNCATE TABLE " + properties.getTable());
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + properties.getTable(), Long.class);
        return count == null ? 0 : count;
    }

    private String operator() {
        return switch (properties.getMetric()) {
            case COSINE -> "<=>";
            case DOT -> "<#>";
            case EUCLIDEAN -> "<->";
        };
    }

    private void validateDimensions(List<VectorDocument> documents) {
        if (documents.stream().anyMatch(document -> document.embedding().length != properties.getDimension())) {
            throw new IllegalArgumentException("Unexpected document vector dimension");
        }
    }
}
