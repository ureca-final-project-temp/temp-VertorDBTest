package com.myapp.benchmark;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {
    private Path documentVectors = Path.of("data/embeddings/documents-vectors.jsonl");
    private Path queryDefinitions = Path.of("data/queries/queries.jsonl");
    private Path queryVectors = Path.of("data/embeddings/query-vectors.jsonl");
    private Path resultDirectory = Path.of("benchmark-result");
    private DistanceMetric metric = DistanceMetric.COSINE;
    private List<String> containerNames = new ArrayList<>();
    private int upsertBatchSize = 256;
    private List<Integer> autoTuneCandidates = new ArrayList<>(List.of(10, 20, 40, 80, 120, 200, 400, 800, 1000));

    public Path getDocumentVectors() { return documentVectors; }
    public void setDocumentVectors(Path documentVectors) { this.documentVectors = documentVectors; }
    public Path getQueryDefinitions() { return queryDefinitions; }
    public void setQueryDefinitions(Path queryDefinitions) { this.queryDefinitions = queryDefinitions; }
    public Path getQueryVectors() { return queryVectors; }
    public void setQueryVectors(Path queryVectors) { this.queryVectors = queryVectors; }
    public Path getResultDirectory() { return resultDirectory; }
    public void setResultDirectory(Path resultDirectory) { this.resultDirectory = resultDirectory; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public List<String> getContainerNames() { return List.copyOf(containerNames); }
    public void setContainerNames(List<String> containerNames) {
        this.containerNames = containerNames == null ? new ArrayList<>() : new ArrayList<>(containerNames);
    }
    public int getUpsertBatchSize() { return upsertBatchSize; }
    public void setUpsertBatchSize(int upsertBatchSize) { this.upsertBatchSize = upsertBatchSize; }
    public List<Integer> getAutoTuneCandidates() { return autoTuneCandidates; }
    public void setAutoTuneCandidates(List<Integer> autoTuneCandidates) { this.autoTuneCandidates = autoTuneCandidates; }
}
