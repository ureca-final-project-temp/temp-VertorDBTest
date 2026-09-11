package com.myapp.benchmark;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

public class ResultWriter {
    private static final String CSV_HEADER = "database,index,target_recall,actual_recall,comparison_recall,recall_tolerance,target_met,recall_selection,tuning_recall,average_ms,p50_ms,p95_ms,p99_ms,qps,"
            + "filtered_queries,filtered_recall,filtered_average_ms,filtered_p50_ms,filtered_p95_ms,filtered_p99_ms,"
            + "unfiltered_queries,unfiltered_recall,unfiltered_average_ms,unfiltered_p50_ms,unfiltered_p95_ms,unfiltered_p99_ms,"
            + "cpu_percent,peak_memory_bytes,disk_write_bytes,index_size_bytes,time_to_index_ready_ms,upsert_ms,vector_count,query_executions,concurrency,top_k,warmup_iterations,measurement_iterations,index_parameters,search_parameters,environment,measured_at\n";
    private final ObjectMapper objectMapper;

    public ResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized Artifacts write(List<BenchmarkResult> results, Path resultDirectory) {
        if (results.isEmpty()) throw new IllegalArgumentException("results must not be empty");
        Path rawDirectory = resultDirectory.resolve("raw");
        Path csvDirectory = resultDirectory.resolve("csv");
        Path chartDirectory = resultDirectory.resolve("charts");
        try {
            Files.createDirectories(rawDirectory);
            Files.createDirectories(csvDirectory);
            Files.createDirectories(chartDirectory);
            String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(results.getFirst().measuredAt());
            Path json = rawDirectory.resolve("benchmark-" + runId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), results);

            Path csv = csvDirectory.resolve("vector-db-result.csv");
            if (!Files.exists(csv)) {
                Files.writeString(csv, CSV_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } else if (!Files.readString(csv, StandardCharsets.UTF_8).startsWith(CSV_HEADER)) {
                throw new IllegalStateException("Existing CSV schema is incompatible; use a new benchmark result directory: " + csv);
            }
            StringBuilder rows = new StringBuilder();
            results.forEach(result -> rows.append(toCsv(result)));
            Files.writeString(csv, rows, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            Path chart = chartDirectory.resolve("recall-latency-" + runId + ".svg");
            List<BenchmarkResult> allResults = readAllResults(rawDirectory);
            String plot = scatterPlot(allResults);
            Files.writeString(chart, plot, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(chartDirectory.resolve("recall-latency-latest.svg"), plot, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return new Artifacts(json, csv, chart);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write benchmark results", exception);
        }
    }

    private List<BenchmarkResult> readAllResults(Path rawDirectory) throws IOException {
        List<Path> files;
        try (var paths = Files.list(rawDirectory)) {
            files = paths.filter(path -> path.getFileName().toString().startsWith("benchmark-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        List<BenchmarkResult> results = new ArrayList<>();
        for (Path file : files) {
            BenchmarkResult[] run = objectMapper.readValue(file.toFile(), BenchmarkResult[].class);
            results.addAll(List.of(run));
        }
        return List.copyOf(results);
    }

    public synchronized Path writeGroundTruth(
            Map<String, List<com.myapp.domain.vector.VectorSearchResult>> groundTruth,
            int topK,
            Path resultDirectory
    ) {
        Path rawDirectory = resultDirectory.resolve("raw");
        Path target = rawDirectory.resolve("ground-truth-top" + topK + ".jsonl");
        try {
            Files.createDirectories(rawDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<com.myapp.domain.vector.VectorSearchResult>> entry : groundTruth.entrySet()) {
                    writer.write(objectMapper.writeValueAsString(Map.of(
                            "queryId", entry.getKey(),
                            "topK", entry.getValue().stream().limit(topK).map(com.myapp.domain.vector.VectorSearchResult::id).toList())));
                    writer.newLine();
                }
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write ground truth", exception);
        }
    }

    private String toCsv(BenchmarkResult result) {
        return String.format(Locale.ROOT,
                "%s,%s,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.3f,%s,%s,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s,%s%n",
                csv(result.database()), csv(result.indexType()), result.targetRecall(), result.actualRecall(),
                result.comparisonRecall(), result.recallTolerance(), result.targetMet(),
                csv(result.recallSelection()), csvNumber(result.tuningRecall()),
                result.averageLatencyMs(), result.p50LatencyMs(), result.p95LatencyMs(), result.p99LatencyMs(), result.qps(),
                csvSegment(result.filtered()), csvSegment(result.unfiltered()),
                result.averageCpuPercent(), result.peakMemoryBytes(), result.diskWriteBytes(), result.indexSizeBytes(),
                result.indexBuildTimeMs(), result.upsertTimeMs(), result.vectorCount(), result.queryExecutions(),
                result.concurrency(), result.topK(), result.warmupIterations(), result.measurementIterations(),
                csv(objectMapper.writeValueAsString(result.indexParameters())),
                csv(objectMapper.writeValueAsString(result.searchParameters())),
                csv(objectMapper.writeValueAsString(result.environment())), result.measuredAt());
    }

    private String csvSegment(QuerySegment segment) {
        return String.format(Locale.ROOT, "%d,%s,%.6f,%.6f,%.6f,%.6f",
                segment.queryExecutions(), csvNumber(segment.recall()),
                segment.averageMs(), segment.p50Ms(), segment.p95Ms(), segment.p99Ms());
    }

    private String csvNumber(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * Filtered queries sit in the tail of the combined distribution, so the combined p95 reports
     * filter cost rather than ANN tail behaviour. Plot the unfiltered tail whenever the run recorded it.
     */
    private double chartLatencyMs(BenchmarkResult result) {
        return result.unfiltered().queryExecutions() > 0 ? result.unfiltered().p95Ms() : result.p95LatencyMs();
    }

    private double chartRecall(BenchmarkResult result) {
        return result.comparisonRecall();
    }

    private String scatterPlot(List<BenchmarkResult> results) {
        double maxLatency = Math.max(1, results.stream().mapToDouble(this::chartLatencyMs).max().orElse(1));
        StringBuilder circles = new StringBuilder();
        String[] colors = {"#2563eb", "#dc2626", "#059669", "#7c3aed", "#ea580c"};
        Map<String, String> databaseColors = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            BenchmarkResult result = results.get(i);
            double latency = chartLatencyMs(result);
            double x = 70 + (latency / maxLatency) * 680;
            double recall = chartRecall(result);
            double y = 350 - recall * 300;
            String color = databaseColors.computeIfAbsent(result.database(),
                    ignored -> colors[databaseColors.size() % colors.length]);
            circles.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"6\" fill=\"%s\"><title>%s / target %.2f / unfiltered p95 %.3f ms / comparison recall %.4f</title></circle>%n",
                    x, y, color, escapeXml(result.database()), result.targetRecall(), latency, recall));
        }
        StringBuilder legend = new StringBuilder();
        int legendX = 90;
        for (Map.Entry<String, String> entry : databaseColors.entrySet()) {
            legend.append("<circle cx=\"").append(legendX).append("\" cy=\"22\" r=\"5\" fill=\"")
                    .append(entry.getValue()).append("\"/>")
                    .append("<text x=\"").append(legendX + 9).append("\" y=\"26\" font-family=\"sans-serif\" font-size=\"11\">")
                    .append(escapeXml(entry.getKey())).append("</text>\n");
            legendX += 110;
        }
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="800" height="420" viewBox="0 0 800 420">
                  <rect width="800" height="420" fill="white"/>
                  <line x1="70" y1="350" x2="760" y2="350" stroke="#111827"/>
                  <line x1="70" y1="40" x2="70" y2="350" stroke="#111827"/>
                  <text x="330" y="400" font-family="sans-serif" font-size="14">unfiltered p95 latency (ms)</text>
                  <text x="18" y="210" transform="rotate(-90 18 210)" font-family="sans-serif" font-size="14">unfiltered Recall@K</text>
                  <text x="70" y="370" font-family="sans-serif" font-size="11">0</text>
                  <text x="720" y="370" font-family="sans-serif" font-size="11">%.2f</text>
                  <text x="42" y="54" font-family="sans-serif" font-size="11">1.0</text>
                %s%s</svg>
                """.formatted(maxLatency, legend, circles);
    }

    private String escapeXml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record Artifacts(Path json, Path csv, Path chart) {
    }
}
