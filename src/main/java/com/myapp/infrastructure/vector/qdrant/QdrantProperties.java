package com.myapp.infrastructure.vector.qdrant;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vector.qdrant")
public class QdrantProperties {
    private String baseUrl = "http://localhost:6333";
    private String apiKey = "";
    private String collection = "benchmark_chunks";
    private int dimension = 384;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private int hnswM = 16;
    private int efConstruction = 128;
    private int defaultEfSearch = 100;
    private int fullScanThreshold = 10;
    private int indexingThreshold = 10;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getEfConstruction() { return efConstruction; }
    public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
    public int getDefaultEfSearch() { return defaultEfSearch; }
    public void setDefaultEfSearch(int defaultEfSearch) { this.defaultEfSearch = defaultEfSearch; }
    public int getFullScanThreshold() { return fullScanThreshold; }
    public void setFullScanThreshold(int fullScanThreshold) { this.fullScanThreshold = fullScanThreshold; }
    public int getIndexingThreshold() { return indexingThreshold; }
    public void setIndexingThreshold(int indexingThreshold) { this.indexingThreshold = indexingThreshold; }
}
