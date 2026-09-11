package com.myapp.benchmark;

import com.myapp.dataset.QuerySetLoader;
import com.myapp.dataset.VectorDatasetLoader;
import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.port.VectorIndexManager;
import com.myapp.port.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class BenchmarkRunner {
    private final ObjectProvider<VectorStore> storeProvider;
    private final ObjectProvider<VectorIndexManager> indexManagerProvider;
    private final BenchmarkProperties properties;
    private final VectorDatasetLoader vectorDatasetLoader;
    private final QuerySetLoader querySetLoader;
    private final RecallCalculator recallCalculator = new RecallCalculator();
    private final ResourceCollector resourceCollector = new ResourceCollector();
    private final BenchmarkEnvironmentCollector environmentCollector = new BenchmarkEnvironmentCollector();
    private final ResultWriter resultWriter;

    public BenchmarkRunner(
            ObjectProvider<VectorStore> storeProvider,
            ObjectProvider<VectorIndexManager> indexManagerProvider,
            BenchmarkProperties properties,
            ObjectMapper objectMapper
    ) {
        this.storeProvider = storeProvider;
        this.indexManagerProvider = indexManagerProvider;
        this.properties = properties;
        this.vectorDatasetLoader = new VectorDatasetLoader(objectMapper);
        this.querySetLoader = new QuerySetLoader(objectMapper);
        this.resultWriter = new ResultWriter(objectMapper);
    }

    public RunOutput run(List<BenchmarkScenario> scenarios, boolean rebuildAndLoad) {
        if (scenarios == null || scenarios.isEmpty()) throw new IllegalArgumentException("At least one scenario is required");
        VectorStore store = requiredStore();
        VectorIndexManager indexManager = requiredIndexManager();
        validateScenarios(scenarios, store, indexManager);

        List<VectorDocument> documents = vectorDatasetLoader.load(properties.getDocumentVectors());
        List<BenchmarkQuery> queries = querySetLoader.load(properties.getQueryDefinitions(), properties.getQueryVectors());
        validateDimensions(documents, queries, store);
        Map<String, Object> environment = environmentCollector.collect(properties);

        long indexBuildTimeMs = 0;
        long upsertTimeMs = 0;
        if (rebuildAndLoad) {
            long timeToReadyStarted = System.nanoTime();
            indexManager.rebuild();
            long started = System.nanoTime();
            upsertInBatches(store, documents);
            upsertTimeMs = elapsedMs(started);
            indexManager.awaitReady(documents.size(), Duration.ofMinutes(5));
            indexBuildTimeMs = elapsedMs(timeToReadyStarted);
        }
        long storedVectors = store.count();
        if (storedVectors != documents.size()) {
            throw new IllegalStateException("Vector count mismatch: expected " + documents.size() + " but store has " + storedVectors);
        }

        ExactSearchEngine exactSearch = new ExactSearchEngine(documents, properties.getMetric());
        Map<Integer, Map<String, List<VectorSearchResult>>> groundTruthByTopK = new LinkedHashMap<>();
        Map<TuningProfile, List<RecallTargetSelector.Candidate<Map<String, Object>>>> tuningCache = new LinkedHashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();
        for (BenchmarkScenario scenario : scenarios) {
            Map<String, List<VectorSearchResult>> groundTruth = groundTruthByTopK.computeIfAbsent(
                    scenario.topK(), ignored -> exactGroundTruth(exactSearch, queries, scenario));
            results.add(runScenario(store, indexManager, documents, queries, scenario, groundTruth,
                    environment, indexBuildTimeMs, upsertTimeMs, tuningCache));
            indexBuildTimeMs = 0;
            upsertTimeMs = 0;
        }
        ResultWriter.Artifacts artifacts = resultWriter.write(results, properties.getResultDirectory());
        return new RunOutput(List.copyOf(results), artifacts);
    }

    private BenchmarkResult runScenario(
            VectorStore store,
            VectorIndexManager indexManager,
            List<VectorDocument> documents,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<String, Object> environment,
            long indexBuildTimeMs,
            long upsertTimeMs,
            Map<TuningProfile, List<RecallTargetSelector.Candidate<Map<String, Object>>>> tuningCache
    ) {
        resultWriter.writeGroundTruth(groundTruth, scenario.topK(), properties.getResultDirectory());
        TuningSelection tuning = scenario.searchParameters().isEmpty()
                ? tuneSearchParameters(store, indexManager, queries, scenario, groundTruth, tuningCache)
                : new TuningSelection(scenario.searchParameters(), null, "EXPLICIT_PARAMETERS");
        Map<String, Object> effectiveParameters = tuning.parameters();
        indexManager.configureSearch(effectiveParameters);
        for (int pass = 0; pass < scenario.warmupIterations(); pass++) {
            for (BenchmarkQuery query : queries) store.search(request(query, scenario, effectiveParameters));
        }

        LatencyCollector latency = new LatencyCollector();
        List<Callable<Double>> tasks = new ArrayList<>();
        for (int pass = 0; pass < scenario.measurementIterations(); pass++) {
            for (BenchmarkQuery query : queries) {
                tasks.add(() -> {
                    VectorSearchRequest request = request(query, scenario, effectiveParameters);
                    long started = System.nanoTime();
                    List<VectorSearchResult> approximate = store.search(request);
                    latency.record(System.nanoTime() - started);
                    return recallCalculator.recallAtK(groundTruth.get(query.queryId()), approximate, scenario.topK());
                });
            }
        }

        List<Double> recallValues = new ArrayList<>(tasks.size());
        long benchmarkStarted;
        ResourceCollector.Usage resourceUsage;
        try (ResourceCollector.Measurement resources = resourceCollector.start(properties.getContainerNames());
             ExecutorService executor = Executors.newFixedThreadPool(scenario.concurrency())) {
            benchmarkStarted = System.nanoTime();
            List<Future<Double>> futures = executor.invokeAll(tasks);
            for (Future<Double> future : futures) recallValues.add(future.get());
            long elapsed = System.nanoTime() - benchmarkStarted;
            resourceUsage = resources.usage();
            LatencyCollector.Statistics stats = latency.statistics();
            double recall = recallValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double qps = tasks.size() / (elapsed / 1_000_000_000.0);
            double recallTolerance = properties.getTargetRecallTolerance();
            return new BenchmarkResult(
                    store.database(), indexManager.indexType(), scenario.targetRecall(), recall,
                    recallTolerance,
                    RecallTargetSelector.withinTolerance(recall, scenario.targetRecall(), recallTolerance),
                    tuning.strategy(), tuning.tuningRecall(),
                    stats.averageMs(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), qps,
                    resourceUsage.averageCpuPercent(), resourceUsage.peakMemoryBytes(), resourceUsage.diskWriteBytes(),
                    indexManager.indexSizeBytes(), indexBuildTimeMs, upsertTimeMs, documents.size(), tasks.size(),
                    scenario.concurrency(), scenario.topK(), scenario.warmupIterations(), scenario.measurementIterations(),
                    indexManager.indexParameters(), effectiveParameters, environment, Instant.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("A vector search failed", exception.getCause());
        }
    }

    private Map<String, List<VectorSearchResult>> exactGroundTruth(
            ExactSearchEngine exactSearch,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario
    ) {
        Map<String, List<VectorSearchResult>> groundTruth = new LinkedHashMap<>();
        for (BenchmarkQuery query : queries) {
            groundTruth.put(query.queryId(), exactSearch.search(request(query, scenario, Map.of())));
        }
        return Map.copyOf(groundTruth);
    }

    private VectorSearchRequest request(BenchmarkQuery query, BenchmarkScenario scenario, Map<String, Object> parameters) {
        return new VectorSearchRequest(query.embedding(), scenario.topK(), query.filter(), parameters);
    }

    private TuningSelection tuneSearchParameters(
            VectorStore store,
            VectorIndexManager indexManager,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<TuningProfile, List<RecallTargetSelector.Candidate<Map<String, Object>>>> tuningCache
    ) {
        String key = switch (store.database().toLowerCase()) {
            case "qdrant" -> "hnsw_ef";
            case "weaviate", "milvus" -> "ef";
            default -> "ef_search";
        };
        List<Integer> candidates = properties.getAutoTuneCandidates() == null
                ? List.of()
                : properties.getAutoTuneCandidates().stream()
                        .filter(candidate -> candidate != null && candidate >= scenario.topK())
                        .distinct()
                        .sorted()
                        .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("benchmark.auto-tune-candidates must contain a value >= topK");
        }
        TuningProfile profile = new TuningProfile(
                scenario.topK(), scenario.concurrency(), scenario.warmupIterations(), scenario.measurementIterations());
        List<RecallTargetSelector.Candidate<Map<String, Object>>> measured = tuningCache.get(profile);
        if (measured == null) {
            measured = measureTuningCandidates(store, indexManager, queries, scenario, groundTruth, key, candidates);
            tuningCache.put(profile, measured);
        }
        RecallTargetSelector.Selection<Map<String, Object>> selection = RecallTargetSelector.select(
                measured, scenario.targetRecall(), properties.getTargetRecallTolerance());
        return new TuningSelection(selection.value(), selection.recall(), selection.strategy().name());
    }

    private List<RecallTargetSelector.Candidate<Map<String, Object>>> measureTuningCandidates(
            VectorStore store,
            VectorIndexManager indexManager,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            String parameterKey,
            List<Integer> candidates
    ) {
        List<RecallTargetSelector.Candidate<Map<String, Object>>> measured = new ArrayList<>(candidates.size());
        for (int candidate : candidates) {
            Map<String, Object> parameters = Map.of(parameterKey, candidate);
            indexManager.configureSearch(parameters);
            for (int pass = 0; pass < scenario.warmupIterations(); pass++) {
                for (BenchmarkQuery query : queries) store.search(request(query, scenario, parameters));
            }
            double recall = recallUnderMeasurementConcurrency(store, queries, scenario, groundTruth, parameters);
            measured.add(new RecallTargetSelector.Candidate<>(parameters, recall));
        }
        return List.copyOf(measured);
    }

    private double recallUnderMeasurementConcurrency(
            VectorStore store,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<String, Object> parameters
    ) {
        List<Callable<Double>> tasks = new ArrayList<>(queries.size() * scenario.measurementIterations());
        for (int pass = 0; pass < scenario.measurementIterations(); pass++) {
            for (BenchmarkQuery query : queries) {
                tasks.add(() -> recallCalculator.recallAtK(
                        groundTruth.get(query.queryId()),
                        store.search(request(query, scenario, parameters)),
                        scenario.topK()));
            }
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(scenario.concurrency())) {
            List<Double> recalls = new ArrayList<>(tasks.size());
            for (Future<Double> future : executor.invokeAll(tasks)) recalls.add(future.get());
            return recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark auto-tuning was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("A vector search failed during auto-tuning", exception.getCause());
        }
    }

    private void upsertInBatches(VectorStore store, List<VectorDocument> documents) {
        int batchSize = properties.getUpsertBatchSize();
        if (batchSize < 1) throw new IllegalStateException("benchmark.upsert-batch-size must be positive");
        for (int start = 0; start < documents.size(); start += batchSize) {
            store.upsert(documents.subList(start, Math.min(start + batchSize, documents.size())));
        }
    }

    private void validateScenarios(List<BenchmarkScenario> scenarios, VectorStore store, VectorIndexManager manager) {
        double tolerance = properties.getTargetRecallTolerance();
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 1) {
            throw new IllegalStateException("benchmark.target-recall-tolerance must be in [0, 1]");
        }
        for (BenchmarkScenario scenario : scenarios) {
            if (scenario.database() != null && !scenario.database().isBlank() && !scenario.database().equalsIgnoreCase(store.database())) {
                throw new IllegalArgumentException("Scenario database does not match active store: " + store.database());
            }
            if (scenario.indexType() != null && !scenario.indexType().isBlank() && !scenario.indexType().equalsIgnoreCase(manager.indexType())) {
                throw new IllegalArgumentException("Scenario indexType does not match active index: " + manager.indexType());
            }
        }
    }

    private void validateDimensions(List<VectorDocument> documents, List<BenchmarkQuery> queries, VectorStore store) {
        int dimension = documents.getFirst().embedding().length;
        if (queries.stream().anyMatch(query -> query.embedding().length != dimension)) {
            throw new IllegalArgumentException("Document vectors and query vectors must use the same dimension");
        }
        if (dimension != store.dimension()) {
            throw new IllegalArgumentException("Input vector dimension " + dimension
                    + " does not match active store dimension " + store.dimension());
        }
        if (properties.getMetric() != store.metric()) {
            throw new IllegalArgumentException("Benchmark metric " + properties.getMetric()
                    + " does not match active store metric " + store.metric());
        }
    }

    private VectorStore requiredStore() {
        VectorStore store = storeProvider.getIfAvailable();
        if (store == null) throw new IllegalStateException("No vector store is active. Enable a vector DB Spring profile.");
        return store;
    }

    private VectorIndexManager requiredIndexManager() {
        VectorIndexManager manager = indexManagerProvider.getIfAvailable();
        if (manager == null) throw new IllegalStateException("No vector index manager is active.");
        return manager;
    }

    private long elapsedMs(long startedNanoseconds) {
        return (System.nanoTime() - startedNanoseconds) / 1_000_000;
    }

    public record RunOutput(List<BenchmarkResult> results, ResultWriter.Artifacts artifacts) {
    }

    private record TuningSelection(Map<String, Object> parameters, Double tuningRecall, String strategy) {
    }

    private record TuningProfile(int topK, int concurrency, int warmupIterations, int measurementIterations) {
    }
}
