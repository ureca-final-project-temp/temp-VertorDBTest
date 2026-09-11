package com.myapp.infrastructure.vector.opensearch;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vector.opensearch")
public class OpenSearchProperties {
    private String baseUrl = "http://localhost:9200";
    private String username = "";
    private String password = "";
    private String index = "benchmark-chunks";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private int hnswM = 16;
    private int efConstruction = 128;
    private int defaultEfSearch = 100;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getIndex() { return index; }
    public void setIndex(String index) { this.index = index; }
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
}
