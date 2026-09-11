package com.myapp.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorDatasetLoaderTest {
    @TempDir
    Path directory;

    @Test
    void readsJsonlAndAlternativeFieldNames() throws IOException {
        Path file = directory.resolve("vectors.jsonl");
        Files.writeString(file, """
                {"id":"chunk-1","document_id":"doc-1","text":"hello","vector":[1.0,0.0],"metadata":{"tenant":"a"}}
                """);

        var documents = new VectorDatasetLoader(JsonMapper.builder().build()).load(file);

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().documentId()).isEqualTo("doc-1");
        assertThat(documents.getFirst().embedding()).containsExactly(1.0f, 0.0f);
        assertThat(documents.getFirst().metadata()).containsEntry("tenant", "a");
    }

    @Test
    void rejectsMixedDimensions() throws IOException {
        Path file = directory.resolve("vectors.jsonl");
        Files.writeString(file, """
                {"id":"one","documentId":"doc","embedding":[1.0,0.0]}
                {"id":"two","documentId":"doc","embedding":[1.0,0.0,0.0]}
                """);

        assertThatThrownBy(() -> new VectorDatasetLoader(JsonMapper.builder().build()).load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same dimension");
    }
}
