package com.myapp.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySetLoaderTest {
    @TempDir
    Path directory;

    @Test
    void joinsDefinitionsAndPrecomputedVectorsByQueryId() throws IOException {
        Path definitions = directory.resolve("queries.jsonl");
        Path vectors = directory.resolve("query-vectors.jsonl");
        Files.writeString(definitions, "{\"queryId\":\"q-1\",\"query\":\"hello\",\"filter\":{\"tenant\":\"a\"}}\n");
        Files.writeString(vectors, "{\"queryId\":\"q-1\",\"embedding\":[0.1,0.2]}\n");

        var queries = new QuerySetLoader(JsonMapper.builder().build()).load(definitions, vectors);

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().query()).isEqualTo("hello");
        assertThat(queries.getFirst().filter().equals()).containsEntry("tenant", "a");
    }
}
