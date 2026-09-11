package com.myapp.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultWriterTest {
    private final ResultWriter writer = new ResultWriter(JsonMapper.builder().build());

    @Test
    void writesOneCsvColumnPerHeaderColumn(@TempDir Path directory) {
        writer.write(List.of(result(new QuerySegment(150, 0.71, 40, 38, 90, 120),
                new QuerySegment(1350, 0.96, 4, 3.5, 7, 12))), directory);

        List<String> lines = readCsv(directory);
        assertThat(lines).hasSize(2);
        assertThat(columns(lines.getFirst())).hasSize(49);
        assertThat(columns(lines.get(1))).hasSize(columns(lines.getFirst()).size());
        assertThat(lines.getFirst()).contains("filtered_p95_ms", "unfiltered_p95_ms", "unfiltered_recall");
        assertThat(lines.getFirst()).contains("comparison_recall");
        assertThat(lines.getFirst()).contains("calibration_selection", "calibration_recall",
                "stability_verified", "stability_diagnostics");
        assertThat(lines.get(1)).contains("90.000000", "7.000000");
    }

    @Test
    void plotsUnfilteredRecallAgainstUnfilteredLatency(@TempDir Path directory) throws Exception {
        writer.write(List.of(result(new QuerySegment(150, 0.71, 40, 38, 90, 120),
                new QuerySegment(1350, 0.96, 4, 3.5, 7, 12))), directory);

        String chart = Files.readString(directory.resolve("charts/recall-latency-latest.svg"));

        assertThat(chart).contains("unfiltered Recall@K", "comparison recall 0.9600", "cy=\"62.00\"");
        assertThat(chart).doesNotContain("comparison recall 0.9412");
    }

    @Test
    void leavesRecallBlankForAnAbsentSegment(@TempDir Path directory) {
        writer.write(List.of(result(QuerySegment.EMPTY, new QuerySegment(1500, 0.94, 4, 3.5, 7, 12))), directory);

        List<String> lines = readCsv(directory);
        int recallColumn = columns(lines.getFirst()).indexOf("filtered_recall");
        assertThat(columns(lines.get(1)).get(recallColumn)).isEmpty();
        assertThat(columns(lines.get(1)).get(recallColumn - 1)).isEqualTo("0");
    }

    private List<String> readCsv(Path directory) {
        try {
            return Files.readAllLines(directory.resolve("csv/vector-db-result.csv"), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Splits on commas outside quoted cells, which is enough for the values this writer emits. */
    private List<String> columns(String line) {
        return List.of(line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1));
    }

    private BenchmarkResult result(QuerySegment filtered, QuerySegment unfiltered) {
        Double comparisonRecall = unfiltered.recall() == null ? 0.9412 : unfiltered.recall();
        return new BenchmarkResult("T05", 1, "qdrant", "Native", "hnsw", 0.95, 0.9412, comparisonRecall,
                0.01, true, "WITHIN_TOLERANCE", 0.9412,
                8.1, 3.6, 12.0, 40.0, 1820.5, filtered, unfiltered,
                131.6, 190.2, 95_000_000L, 102_000_000L, 4_096L, -1L, 5_143L, 3_891L, 10_000, 1_500, 10, 10, 1, 5,
                StabilityDiagnostics.notRequired(),
                Map.of("m", 16), Map.of("hnsw_ef", 400), Map.of("metric", "COSINE"), Instant.now());
    }
}
