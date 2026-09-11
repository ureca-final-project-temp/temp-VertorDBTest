package com.myapp.tools;

import com.myapp.infrastructure.embedding.OllamaEmbeddingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates immutable benchmark JSONL files from the bundled corpus and a local Ollama model.
 * Partial outputs are resumable and are moved to their final names only after full validation.
 */
public final class EmbeddingDatasetGenerator {
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Config config;
    private final OllamaEmbeddingClient client;

    private EmbeddingDatasetGenerator(Config config) {
        this.config = config;
        this.client = new OllamaEmbeddingClient(config.ollamaUrl(), config.model(), objectMapper);
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);
        new EmbeddingDatasetGenerator(config).run();
    }

    private void run() {
        System.out.printf("Embedding model=%s dimension=%d batch=%d endpoint=%s%n",
                config.model(), config.dimension(), config.batchSize(), config.ollamaUrl());
        String digest = client.modelDigest();
        GenerationResult documents = generate(config.documents(), config.documentOutput(), RecordType.DOCUMENT);
        GenerationResult queries = generate(config.queries(), config.queryOutput(), RecordType.QUERY);
        copyQueryDefinitions();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("provider", "ollama");
        manifest.put("endpoint", config.ollamaUrl());
        manifest.put("model", config.model());
        manifest.put("modelDigest", digest);
        manifest.put("dimension", config.dimension());
        manifest.put("batchSize", config.batchSize());
        manifest.put("queryInstruction", "none");
        manifest.put("documentInput", fileMetadata(config.documents()));
        manifest.put("queryInput", fileMetadata(config.queries()));
        manifest.put("documentOutput", documents.toMap());
        manifest.put("queryOutput", queries.toMap());
        writeJsonAtomically(config.manifestOutput(), manifest);
        System.out.printf("Completed: documents=%d queries=%d manifest=%s%n",
                documents.records(), queries.records(), config.manifestOutput());
    }

    private GenerationResult generate(Path source, Path output, RecordType type) {
        requireReadableFile(source);
        String inputHash = sha256(source);
        long expectedRecords = countNonBlankLines(source);
        Path partial = sibling(output, output.getFileName() + ".partial");
        Path checkpoint = sibling(output, output.getFileName() + ".checkpoint.json");

        if (config.overwrite()) {
            deleteIfExists(output);
            deleteIfExists(partial);
            deleteIfExists(checkpoint);
        }
        if (Files.exists(output)) {
            if (Files.exists(partial) || Files.exists(checkpoint)) {
                throw new IllegalStateException("Final and partial embedding outputs coexist: " + output);
            }
            GenerationResult existing = inspect(output, type, expectedRecords);
            System.out.printf("Already complete: %s (%d records)%n", output, existing.records());
            return existing;
        }

        createParent(output);
        long completed = preparePartial(source, partial, checkpoint, type, inputHash);
        long started = System.nanoTime();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            skipNonBlank(reader, completed);
            List<InputRecord> batch = new ArrayList<>(config.batchSize());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(type.read(objectMapper.readTree(line), objectMapper));
                if (batch.size() == config.batchSize()) {
                    completed = embedAndWrite(batch, writer, completed, checkpoint, source, inputHash);
                    progress(type, completed, expectedRecords, started);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                completed = embedAndWrite(batch, writer, completed, checkpoint, source, inputHash);
                progress(type, completed, expectedRecords, started);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate embeddings from " + source, exception);
        }
        if (completed != expectedRecords) {
            throw new IllegalStateException("Generated record count mismatch for " + source
                    + ": expected " + expectedRecords + " but got " + completed);
        }
        moveAtomically(partial, output);
        deleteIfExists(checkpoint);
        return inspect(output, type, expectedRecords);
    }

    private long preparePartial(
            Path source,
            Path partial,
            Path checkpoint,
            RecordType type,
            String inputHash
    ) {
        if (!Files.exists(partial)) {
            if (Files.exists(checkpoint)) throw new IllegalStateException("Checkpoint exists without partial output: " + checkpoint);
            writeCheckpoint(checkpoint, source, inputHash, 0);
            return 0;
        }
        if (!Files.exists(checkpoint)) throw new IllegalStateException("Partial output has no checkpoint: " + partial);
        JsonNode state = readJson(checkpoint);
        requireCheckpoint(state, "model", config.model());
        requireCheckpoint(state, "inputSha256", inputHash);
        if (state.get("dimension").asInt() != config.dimension()) {
            throw new IllegalStateException("Partial output dimension does not match current configuration");
        }
        long completed = validatePartial(source, partial, type);
        if (state.get("completedRecords").asLong() != completed) {
            throw new IllegalStateException("Checkpoint count does not match partial output: " + partial);
        }
        System.out.printf("Resuming %s at record %d%n", partial, completed);
        return completed;
    }

    private long embedAndWrite(
            List<InputRecord> batch,
            BufferedWriter writer,
            long completed,
            Path checkpoint,
            Path source,
            String inputHash
    ) throws IOException {
        List<float[]> vectors = embedResilient(batch.stream().map(InputRecord::text).toList());
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = vectors.get(i);
            validateVector(vector);
            Map<String, Object> row = new LinkedHashMap<>(batch.get(i).output());
            row.put("embedding", vector);
            writer.write(objectMapper.writeValueAsString(row));
            writer.newLine();
        }
        writer.flush();
        long next = completed + batch.size();
        writeCheckpoint(checkpoint, source, inputHash, next);
        return next;
    }

    private List<float[]> embedResilient(List<String> texts) {
        try {
            return client.embed(texts);
        } catch (IllegalStateException exception) {
            if (texts.size() <= 16) throw exception;
            int middle = texts.size() / 2;
            System.err.printf("Embedding batch of %d failed after retries; splitting into %d and %d.%n",
                    texts.size(), middle, texts.size() - middle);
            List<float[]> vectors = new ArrayList<>(texts.size());
            vectors.addAll(embedResilient(texts.subList(0, middle)));
            vectors.addAll(embedResilient(texts.subList(middle, texts.size())));
            return List.copyOf(vectors);
        }
    }

    private void writeCheckpoint(Path checkpoint, Path source, String inputHash, long completed) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("source", source.toString());
        state.put("inputSha256", inputHash);
        state.put("model", config.model());
        state.put("dimension", config.dimension());
        state.put("completedRecords", completed);
        writeJsonAtomically(checkpoint, state);
    }

    private long validatePartial(Path source, Path partial, RecordType type) {
        long count = 0;
        try (BufferedReader inputs = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             BufferedReader outputs = Files.newBufferedReader(partial, StandardCharsets.UTF_8)) {
            String outputLine;
            while ((outputLine = outputs.readLine()) != null) {
                if (outputLine.isBlank()) continue;
                String inputLine = nextNonBlank(inputs);
                if (inputLine == null) throw new IllegalStateException("Partial output is longer than source: " + partial);
                JsonNode input = objectMapper.readTree(inputLine);
                JsonNode output = objectMapper.readTree(outputLine);
                String expectedId = type.id(input);
                String actualId = type.id(output);
                if (!expectedId.equals(actualId)) {
                    throw new IllegalStateException("Partial output order mismatch at record " + count);
                }
                validateVector(readVector(output));
                count++;
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot validate partial output " + partial, exception);
        }
    }

    private GenerationResult inspect(Path output, RecordType type, long expectedRecords) {
        long records = 0;
        double minNorm = Double.POSITIVE_INFINITY;
        double maxNorm = 0;
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode row = objectMapper.readTree(line);
                type.id(row);
                float[] vector = readVector(row);
                validateVector(vector);
                double norm = norm(vector);
                minNorm = Math.min(minNorm, norm);
                maxNorm = Math.max(maxNorm, norm);
                records++;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect generated output " + output, exception);
        }
        if (records != expectedRecords) {
            throw new IllegalStateException("Existing output count mismatch for " + output
                    + ": expected " + expectedRecords + " but got " + records);
        }
        return new GenerationResult(output, records, sha256(output), minNorm, maxNorm);
    }

    private void copyQueryDefinitions() {
        Path destination = config.queryDefinitionsOutput();
        String sourceHash = sha256(config.queries());
        if (Files.exists(destination) && !config.overwrite()) {
            if (!sourceHash.equals(sha256(destination))) {
                throw new IllegalStateException("Query definitions output exists with different content: " + destination);
            }
            return;
        }
        createParent(destination);
        Path temporary = sibling(destination, destination.getFileName() + ".tmp");
        try {
            Files.copy(config.queries(), temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot copy query definitions", exception);
        }
    }

    private Map<String, Object> fileMetadata(Path path) {
        return Map.of("path", path.toString(), "records", countNonBlankLines(path), "sha256", sha256(path));
    }

    private void validateVector(float[] vector) {
        if (vector.length != config.dimension()) {
            throw new IllegalStateException("Expected vector dimension " + config.dimension() + " but got " + vector.length);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Embedding contains a non-finite value");
        }
        if (norm(vector) == 0) throw new IllegalStateException("Embedding vector must not be zero");
    }

    private float[] readVector(JsonNode node) {
        JsonNode embedding = node.get("embedding");
        if (embedding == null || !embedding.isArray()) throw new IllegalStateException("Output record has no embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) vector[i] = embedding.get(i).asFloat();
        return vector;
    }

    private void progress(RecordType type, long completed, long total, long started) {
        double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
        double rate = completed / seconds;
        System.out.printf("%s %,d/%,d (%.1f%%, %.1f records/s)%n",
                type.name().toLowerCase(), completed, total, completed * 100.0 / total, rate);
    }

    private JsonNode readJson(Path path) {
        return objectMapper.readTree(path.toFile());
    }

    private void requireCheckpoint(JsonNode state, String field, String expected) {
        JsonNode value = state.get(field);
        if (value == null || !expected.equals(value.asString())) {
            throw new IllegalStateException("Partial output checkpoint has a different " + field);
        }
    }

    private void writeJsonAtomically(Path target, Object value) {
        createParent(target);
        Path temporary = sibling(target, target.getFileName() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            moveAtomically(temporary, target);
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private static void moveAtomically(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot move " + source + " to " + target, exception);
        }
    }

    private static void createParent(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create output directory for " + path, exception);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete generated output " + path, exception);
        }
    }

    private static void requireReadableFile(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("Input file is not readable: " + path);
        }
    }

    private static long countNonBlankLines(Path path) {
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot count records in " + path, exception);
        }
    }

    private static void skipNonBlank(BufferedReader reader, long records) throws IOException {
        for (long skipped = 0; skipped < records; ) {
            String line = reader.readLine();
            if (line == null) throw new IllegalStateException("Partial output is longer than input");
            if (!line.isBlank()) skipped++;
        }
    }

    private static String nextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) if (!line.isBlank()) return line;
        return null;
    }

    private static double norm(float[] vector) {
        double squared = 0;
        for (float value : vector) squared += value * value;
        return Math.sqrt(squared);
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash " + path, exception);
        }
    }

    private static Path sibling(Path path, String fileName) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) throw new IllegalArgumentException("Output path must have a parent: " + path);
        return parent.resolve(fileName);
    }

    private record InputRecord(String id, String text, Map<String, Object> output) {
        private InputRecord {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Embedding input id must not be blank");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input text must not be blank: " + id);
        }
    }

    private enum RecordType {
        DOCUMENT {
            @Override
            InputRecord read(JsonNode node, ObjectMapper objectMapper) {
                String id = text(node, "id", "chunkId", "chunk_id");
                String documentId = text(node, "documentId", "document_id");
                String chunkId = text(node, "chunkId", "chunk_id", "id");
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("id", id);
                output.put("documentId", documentId == null ? id : documentId);
                output.put("chunkId", chunkId == null ? id : chunkId);
                output.put("content", text(node, "content", "text"));
                output.put("metadata", metadata(node, objectMapper));
                return new InputRecord(id, text(node, "content", "text"), output);
            }
        },
        QUERY {
            @Override
            InputRecord read(JsonNode node, ObjectMapper objectMapper) {
                String id = text(node, "queryId", "query_id", "id");
                return new InputRecord(id, text(node, "query", "text"), new LinkedHashMap<>(Map.of("queryId", id)));
            }
        };

        abstract InputRecord read(JsonNode node, ObjectMapper objectMapper);

        String id(JsonNode node) {
            return this == DOCUMENT
                    ? text(node, "id", "chunkId", "chunk_id")
                    : text(node, "queryId", "query_id", "id");
        }

        private static String text(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull() && !value.asString().isBlank()) return value.asString();
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> metadata(JsonNode node, ObjectMapper objectMapper) {
            JsonNode nested = node.get("metadata");
            if (nested != null && nested.isObject()) return objectMapper.treeToValue(nested, Map.class);
            Map<String, Object> source = objectMapper.treeToValue(node, Map.class);
            Map<String, Object> metadata = new LinkedHashMap<>(source);
            List.of("id", "documentId", "document_id", "chunkId", "chunk_id", "content", "text", "embedding", "vector")
                    .forEach(metadata::remove);
            return Map.copyOf(metadata);
        }
    }

    private record GenerationResult(Path path, long records, String sha256, double minL2Norm, double maxL2Norm) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", path.toString());
            values.put("records", records);
            values.put("sha256", sha256);
            values.put("minL2Norm", minL2Norm);
            values.put("maxL2Norm", maxL2Norm);
            return Map.copyOf(values);
        }
    }

    private record Config(
            String ollamaUrl,
            String model,
            int dimension,
            int batchSize,
            Path documents,
            Path queries,
            Path documentOutput,
            Path queryOutput,
            Path queryDefinitionsOutput,
            Path manifestOutput,
            boolean overwrite
    ) {
        static Config parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("Arguments must use --name=value: " + argument);
                }
                String[] parts = argument.substring(2).split("=", 2);
                values.put(parts[0], parts[1]);
            }
            int dimension = integer(values, "dimension", 1024);
            int batchSize = integer(values, "batch-size", 128);
            if (dimension < 1 || batchSize < 1) throw new IllegalArgumentException("dimension and batch-size must be positive");
            return new Config(
                    values.getOrDefault("ollama-url", "http://localhost:11434"),
                    values.getOrDefault("model", "bge-m3:latest"),
                    dimension,
                    batchSize,
                    Path.of(values.getOrDefault("documents", "data/documents_10000.jsonl")),
                    Path.of(values.getOrDefault("queries", "data/queries_300.jsonl")),
                    Path.of(values.getOrDefault("document-output", "data/embeddings/document-vectors.jsonl")),
                    Path.of(values.getOrDefault("query-output", "data/embeddings/query-vectors.jsonl")),
                    Path.of(values.getOrDefault("query-definitions-output", "data/queries/queries.jsonl")),
                    Path.of(values.getOrDefault("manifest-output", "data/embeddings/embedding-manifest.json")),
                    Boolean.parseBoolean(values.getOrDefault("overwrite", "false"))
            );
        }

        private static int integer(Map<String, String> values, String name, int fallback) {
            return Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
        }
    }
}
