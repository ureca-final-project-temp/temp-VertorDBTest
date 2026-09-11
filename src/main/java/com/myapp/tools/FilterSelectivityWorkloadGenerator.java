package com.myapp.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates cardinality-controlled filter inputs while preserving every embedding byte-for-byte. */
public final class FilterSelectivityWorkloadGenerator {
    private static final List<Level> LEVELS = List.of(
            new Level("01", "benchmark_selectivity_01", 0.01),
            new Level("10", "benchmark_selectivity_10", 0.10),
            new Level("50", "benchmark_selectivity_50", 0.50));

    private final ObjectMapper objectMapper;

    FilterSelectivityWorkloadGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        Map<String, String> options = options(args);
        Path documentInput = Path.of(required(options, "document-input"));
        Path queryInput = Path.of(required(options, "query-input"));
        Path outputDirectory = Path.of(required(options, "output-directory"));
        boolean overwrite = Boolean.parseBoolean(options.getOrDefault("overwrite", "false"));
        new FilterSelectivityWorkloadGenerator(JsonMapper.builder().build())
                .generate(documentInput, queryInput, outputDirectory, overwrite);
    }

    public Manifest generate(Path documentInput, Path queryInput, Path outputDirectory, boolean overwrite) {
        try {
            Files.createDirectories(outputDirectory);
            Path documentOutput = outputDirectory.resolve("document-vectors.jsonl");
            Map<Level, Path> queryOutputs = new LinkedHashMap<>();
            for (Level level : LEVELS) queryOutputs.put(level, outputDirectory.resolve("queries-" + level.label() + ".jsonl"));
            List<Path> outputs = new ArrayList<>(queryOutputs.values());
            outputs.add(documentOutput);
            outputs.add(outputDirectory.resolve("manifest.json"));
            if (!overwrite && outputs.stream().anyMatch(Files::exists)) {
                throw new IllegalStateException("Filter-selectivity output already exists; use --overwrite=true: " + outputDirectory);
            }

            List<String> documentIds = readDocumentIds(documentInput);
            Map<Level, Set<String>> selectedIds = selectedIds(documentIds);
            writeDocuments(documentInput, documentOutput, selectedIds);
            int filteredQueries = writeQueries(queryInput, queryOutputs);

            Map<String, Integer> selectedCounts = new LinkedHashMap<>();
            Map<String, String> queryFiles = new LinkedHashMap<>();
            for (Level level : LEVELS) {
                selectedCounts.put(level.label() + "%", selectedIds.get(level).size());
                queryFiles.put(level.label() + "%", queryOutputs.get(level).toString());
            }
            Manifest manifest = new Manifest(documentIds.size(), filteredQueries, documentOutput.toString(),
                    selectedCounts, queryFiles, "sha256(document-id), nested deterministic cohorts");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDirectory.resolve("manifest.json").toFile(), manifest);
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate filter-selectivity workload", exception);
        }
    }

    private List<String> readDocumentIds(Path input) throws IOException {
        List<String> ids = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                JsonNode id = node.get("id");
                if (id == null || id.asString().isBlank()) throw new IllegalArgumentException("Document row is missing id");
                ids.add(id.asString());
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Document input is empty: " + input);
        if (new LinkedHashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Document IDs must be unique");
        return List.copyOf(ids);
    }

    private Map<Level, Set<String>> selectedIds(List<String> ids) {
        List<String> ordered = ids.stream()
                .sorted(Comparator.comparing(FilterSelectivityWorkloadGenerator::sha256).thenComparing(value -> value))
                .toList();
        Map<Level, Set<String>> selected = new LinkedHashMap<>();
        for (Level level : LEVELS) {
            int count = Math.max(1, (int) Math.round(ids.size() * level.ratio()));
            selected.put(level, Set.copyOf(ordered.subList(0, count)));
        }
        return selected;
    }

    private void writeDocuments(Path input, Path output, Map<Level, Set<String>> selected) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ObjectNode document = (ObjectNode) objectMapper.readTree(line);
                String id = document.get("id").asString();
                JsonNode metadataNode = document.get("metadata");
                ObjectNode metadata = metadataNode instanceof ObjectNode object
                        ? object
                        : document.putObject("metadata");
                for (Level level : LEVELS) {
                    metadata.put(level.field(), selected.get(level).contains(id) ? "match" : "other");
                }
                writer.write(objectMapper.writeValueAsString(document));
                writer.newLine();
            }
        }
    }

    private int writeQueries(Path input, Map<Level, Path> outputs) throws IOException {
        Map<Level, BufferedWriter> writers = new LinkedHashMap<>();
        try {
            for (Map.Entry<Level, Path> output : outputs.entrySet()) {
                writers.put(output.getKey(), Files.newBufferedWriter(output.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            }
            int filteredCount = 0;
            try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    ObjectNode source = (ObjectNode) objectMapper.readTree(line);
                    boolean filtered = source.get("filter") != null && source.get("filter").isObject()
                            && !source.get("filter").isEmpty();
                    if (filtered) filteredCount++;
                    for (Level level : LEVELS) {
                        ObjectNode query = source.deepCopy();
                        if (filtered) {
                            ObjectNode filter = query.putObject("filter");
                            filter.put(level.field(), "match");
                            query.put("query_type", "metadata_filter_" + level.label());
                            query.put("expected_filter_selectivity", level.ratio());
                        }
                        writers.get(level).write(objectMapper.writeValueAsString(query));
                        writers.get(level).newLine();
                    }
                }
            }
            if (filteredCount == 0) throw new IllegalArgumentException("Query input has no metadata-filter queries");
            return filteredCount;
        } finally {
            for (BufferedWriter writer : writers.values()) writer.close();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("Expected --key=value: " + arg);
            int separator = arg.indexOf('=');
            values.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }

    private record Level(String label, String field, double ratio) {
    }

    public record Manifest(
            int documentCount,
            int filteredQueryCount,
            String documentFile,
            Map<String, Integer> selectedDocumentCounts,
            Map<String, String> queryFiles,
            String assignmentMethod
    ) {
    }
}
