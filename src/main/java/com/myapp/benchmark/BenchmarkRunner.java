package com.myapp.benchmark;

import com.myapp.dataset.QuerySetLoader;
import com.myapp.dataset.VectorDatasetLoader;
import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.rdb.postgres.BenchmarkSourceOfTruthSynchronizer;
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
import java.util.Locale;
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
    private final ObjectProvider<BenchmarkSourceOfTruthSynchronizer> sourceOfTruthProvider;
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
            ObjectProvider<BenchmarkSourceOfTruthSynchronizer> sourceOfTruthProvider,
            BenchmarkProperties properties,
            ObjectMapper objectMapper
    ) {
        this.storeProvider = storeProvider;
        this.indexManagerProvider = indexManagerProvider;
        this.sourceOfTruthProvider = sourceOfTruthProvider;
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
        QueryPartitioner.Partition queryPartition = QueryPartitioner.split(queries, properties.getCalibrationQueryCount());
        validateDimensions(documents, queries, store);
        Map<String, Object> environmentValues = new LinkedHashMap<>(environmentCollector.collect(properties));
        environmentValues.put("queryPartition", Map.of(
                "method", "query-type-stratified-sha256",
                "calibrationQueries", queryPartition.calibration().size(),
                "evaluationQueries", queryPartition.evaluation().size(),
                "calibrationIdsSha256", queryPartition.calibrationIdsSha256(),
                "evaluationIdsSha256", queryPartition.evaluationIdsSha256()));
        sourceOfTruthProvider.ifAvailable(synchronizer -> environmentValues.put("sourceOfTruth",
                synchronizer.synchronize(documents,
                        environmentValues.get("documentVectorsSha256").toString(), rebuildAndLoad)));
        Map<String, Object> environment = Map.copyOf(environmentValues);

        long indexBuildTimeMs = 0;
        long upsertTimeMs = 0;
        if (rebuildAndLoad) {
            long timeToReadyStarted = System.nanoTime();
            indexManager.rebuild(documents);
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
            results.add(runScenario(store, indexManager, documents, queryPartition.calibration(),
                    queryPartition.evaluation(), scenario, groundTruth,
                    environment, indexBuildTimeMs, upsertTimeMs, tuningCache));
        }
        ResultWriter.Artifacts artifacts = resultWriter.write(results, properties.getResultDirectory());
        return new RunOutput(List.copyOf(results), artifacts);
    }

    private BenchmarkResult runScenario(
            VectorStore store,
            VectorIndexManager indexManager,
            List<VectorDocument> documents,
            List<BenchmarkQuery> calibrationQueries,
            List<BenchmarkQuery> evaluationQueries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<String, Object> environment,
            long indexBuildTimeMs,
            long upsertTimeMs,
            Map<TuningProfile, List<RecallTargetSelector.Candidate<Map<String, Object>>>> tuningCache
    ) {
        resultWriter.writeGroundTruth(groundTruth, scenario.topK(), properties.getResultDirectory());
        TuningSelection tuning = scenario.searchParameters().isEmpty()
                ? tuneSearchParameters(store, indexManager, calibrationQueries, scenario, groundTruth, tuningCache)
                : new TuningSelection(scenario.searchParameters(), null, "EXPLICIT_PARAMETERS");
        Map<String, Object> effectiveParameters = tuning.parameters();
        indexManager.configureSearch(effectiveParameters);
        for (int pass = 0; pass < scenario.warmupIterations(); pass++) {
            for (BenchmarkQuery query : evaluationQueries) store.search(request(query, scenario, effectiveParameters));
        }
        Map<String, Object> stabilityStateBefore = indexManager.requiresStabilityCheck()
                ? indexManager.diagnostics()
                : Map.of();

        LatencyCollector latency = new LatencyCollector();
        List<Callable<QueryOutcome>> tasks = new ArrayList<>();
        for (int pass = 0; pass < scenario.measurementIterations(); pass++) {
            for (BenchmarkQuery query : evaluationQueries) {
                boolean filtered = !query.filter().isEmpty();
                tasks.add(() -> {
                    VectorSearchRequest request = request(query, scenario, effectiveParameters);
                    long started = System.nanoTime();
                    List<VectorSearchResult> approximate = store.search(request);
                    latency.record(System.nanoTime() - started, filtered);
                    return new QueryOutcome(query.queryId(), filtered, approximate);
                });
            }
        }

        List<Double> recallValues = new ArrayList<>(tasks.size());
        List<Double> filteredRecalls = new ArrayList<>();
        List<Double> unfilteredRecalls = new ArrayList<>();
        long benchmarkStarted;
        ResourceCollector.Usage resourceUsage;
        try (ResourceCollector.Measurement resources = resourceCollector.start(properties.getContainerNames());
             ExecutorService executor = Executors.newFixedThreadPool(scenario.concurrency())) {
            benchmarkStarted = System.nanoTime();
            List<Future<QueryOutcome>> futures = executor.invokeAll(tasks);
            long elapsed = System.nanoTime() - benchmarkStarted;
            resourceUsage = resources.usage();
            // Recall is deliberately post-processed after the search workload timer closes.
            for (Future<QueryOutcome> future : futures) {
                QueryOutcome outcome = future.get();
                double recall = recallCalculator.recallAtK(
                        groundTruth.get(outcome.queryId()), outcome.approximate(), scenario.topK());
                recallValues.add(recall);
                (outcome.filtered() ? filteredRecalls : unfilteredRecalls).add(recall);
            }
            LatencyCollector.Statistics stats = latency.statistics();
            double recall = recallValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            QuerySegment filtered = QuerySegment.of(latency.filteredStatistics(), filteredRecalls);
            QuerySegment unfiltered = QuerySegment.of(latency.unfilteredStatistics(), unfilteredRecalls);
            double comparisonRecall = unfiltered.recall() == null ? recall : unfiltered.recall();
            double qps = tasks.size() / (elapsed / 1_000_000_000.0);
            double recallTolerance = properties.getTargetRecallTolerance();
            StabilityDiagnostics stability = diagnoseStability(
                    store, indexManager, calibrationQueries, scenario, groundTruth,
                    effectiveParameters, tuning.calibrationRecall(), stabilityStateBefore);
            return new BenchmarkResult(
                    scenario.testId(), scenario.runNumber(), store.database(), indexManager.engine(), indexManager.indexType(),
                    scenario.targetRecall(), recall, comparisonRecall,
                    recallTolerance,
                    RecallTargetSelector.withinTolerance(comparisonRecall, scenario.targetRecall(), recallTolerance),
                    tuning.strategy(), tuning.calibrationRecall(),
                    stats.averageMs(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), qps,
                    filtered, unfiltered,
                    resourceUsage.averageCpuPercent(), resourceUsage.peakCpuPercent(),
                    resourceUsage.averageMemoryBytes(), resourceUsage.peakMemoryBytes(), resourceUsage.diskWriteBytes(),
                    indexManager.indexSizeBytes(), indexBuildTimeMs, upsertTimeMs, documents.size(), tasks.size(),
                    scenario.concurrency(), scenario.topK(), scenario.warmupIterations(), scenario.measurementIterations(),
                    stability, indexManager.indexParameters(), effectiveParameters, environment, Instant.now());
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
        String key = indexManager.searchParameterName();
        if (key == null || key.isBlank()) {
            return new TuningSelection(Map.of("k", scenario.topK()), null, "FIXED_SEARCH");
        }
        int minimum = indexManager.minimumSearchParameter(scenario.topK());
        int maximum = indexManager.maximumSearchParameter();
        List<Integer> candidates = properties.getAutoTuneCandidates() == null
                ? List.of()
                : properties.getAutoTuneCandidates().stream()
                        .filter(candidate -> candidate != null && candidate >= minimum && candidate <= maximum)
                        .distinct()
                        .sorted()
                        .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("benchmark.auto-tune-candidates has no value in the supported range "
                    + minimum + ".." + maximum + " for " + indexManager.engine() + "/" + indexManager.indexType());
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
        List<BenchmarkQuery> comparisonQueries = queries.stream()
                .filter(query -> query.filter().isEmpty())
                .toList();
        if (comparisonQueries.isEmpty()) comparisonQueries = queries;
        List<RecallTargetSelector.Candidate<Map<String, Object>>> measured = new ArrayList<>(candidates.size());
        for (int candidate : candidates) {
            Map<String, Object> parameters = Map.of(parameterKey, candidate);
            indexManager.configureSearch(parameters);
            for (int pass = 0; pass < scenario.warmupIterations(); pass++) {
                for (BenchmarkQuery query : comparisonQueries) {
                    store.search(request(query, scenario, parameters));
                }
            }
            double recall = recallUnderMeasurementConcurrency(
                    store, comparisonQueries, scenario, groundTruth, parameters);
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
        return recallUnderConcurrency(store, queries, scenario, groundTruth, parameters, scenario.concurrency());
    }

    private double recallUnderConcurrency(
            VectorStore store,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<String, Object> parameters,
            int concurrency
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
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
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

    private StabilityDiagnostics diagnoseStability(
            VectorStore store,
            VectorIndexManager indexManager,
            List<BenchmarkQuery> calibrationQueries,
            BenchmarkScenario scenario,
            Map<String, List<VectorSearchResult>> groundTruth,
            Map<String, Object> parameters,
            Double calibrationRecall,
            Map<String, Object> stateBefore
    ) {
        if (!indexManager.requiresStabilityCheck()) return StabilityDiagnostics.notRequired();
        double threshold = properties.getDriftThreshold();
        int repetitions = properties.getDriftDiagnosticRepetitions();
        if (calibrationRecall == null) {
            return new StabilityDiagnostics(true, false, threshold, null, List.of(), List.of(),
                    stateBefore, indexManager.diagnostics(), "Calibration recall is unavailable");
        }
        List<BenchmarkQuery> comparisonQueries = calibrationQueries.stream()
                .filter(query -> query.filter().isEmpty())
                .toList();
        if (comparisonQueries.isEmpty()) comparisonQueries = calibrationQueries;
        List<Double> serial = new ArrayList<>(repetitions);
        List<Double> concurrent = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            serial.add(recallUnderConcurrency(store, comparisonQueries, scenario, groundTruth, parameters, 1));
            concurrent.add(recallUnderConcurrency(
                    store, comparisonQueries, scenario, groundTruth, parameters, scenario.concurrency()));
        }
        Map<String, Object> stateAfter = indexManager.diagnostics();
        double maximumCalibrationDrift = concurrent.stream()
                .mapToDouble(value -> Math.abs(value - calibrationRecall))
                .max().orElse(Double.POSITIVE_INFINITY);
        double concurrencyEffect = Math.abs(median(serial) - median(concurrent));
        boolean diagnosticStateComplete = !hasDiagnosticError(stateBefore) && !hasDiagnosticError(stateAfter);
        boolean verified = diagnosticStateComplete
                && maximumCalibrationDrift <= threshold
                && concurrencyEffect <= threshold;
        String reason = String.format(Locale.ROOT,
                "max calibration drift=%.6f, serial/concurrent median delta=%.6f, stateComplete=%s",
                maximumCalibrationDrift, concurrencyEffect, diagnosticStateComplete);
        return new StabilityDiagnostics(true, verified, threshold, calibrationRecall,
                serial, concurrent, stateBefore, stateAfter, reason);
    }

    private double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private boolean hasDiagnosticError(Map<String, Object> state) {
        return state.keySet().stream().anyMatch(key -> key.toLowerCase(Locale.ROOT).contains("error"));
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
        if (properties.getDriftDiagnosticRepetitions() < 1 || properties.getDriftDiagnosticRepetitions() > 10) {
            throw new IllegalStateException("benchmark.drift-diagnostic-repetitions must be in [1, 10]");
        }
        if (!Double.isFinite(properties.getDriftThreshold())
                || properties.getDriftThreshold() < 0 || properties.getDriftThreshold() > 1) {
            throw new IllegalStateException("benchmark.drift-threshold must be in [0, 1]");
        }
        for (BenchmarkScenario scenario : scenarios) {
            if (scenario.database() != null && !scenario.database().isBlank() && !scenario.database().equalsIgnoreCase(store.database())) {
                throw new IllegalArgumentException("Scenario database does not match active store: " + store.database());
            }
            if (scenario.indexType() != null && !scenario.indexType().isBlank() && !scenario.indexType().equalsIgnoreCase(manager.indexType())) {
                throw new IllegalArgumentException("Scenario indexType does not match active index: " + manager.indexType());
            }
            if (scenario.engine() != null && !scenario.engine().isBlank() && !scenario.engine().equalsIgnoreCase(manager.engine())) {
                throw new IllegalArgumentException("Scenario engine does not match active engine: " + manager.engine());
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

    private record TuningSelection(Map<String, Object> parameters, Double calibrationRecall, String strategy) {
    }

    private record QueryOutcome(String queryId, boolean filtered, List<VectorSearchResult> approximate) {
    }

    private record TuningProfile(int topK, int concurrency, int warmupIterations, int measurementIterations) {
    }
}
