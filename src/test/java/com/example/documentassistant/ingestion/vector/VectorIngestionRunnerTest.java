package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VectorIngestionRunnerTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void readsChunkDocumentsAndSkipsBatchReport() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    ChunkVectorIngestionService ingestionService = mock(ChunkVectorIngestionService.class);

    ChunkedDocument source = chunkedDocument();

    objectMapper.writeValue(
        temporaryDirectory.resolve("product_1_chunks.json").toFile(),
        source);

    Files.writeString(
        temporaryDirectory.resolve("batch-result.json"),
        """
            {
              "successfulDocuments": 1,
              "failedDocuments": 0
            }
            """);

    VectorIngestionProperties properties = new VectorIngestionProperties(temporaryDirectory);

    VectorIngestionRunner runner = new VectorIngestionRunner(
        objectMapper,
        ingestionService,
        properties);

    runner.run();

    ArgumentCaptor<ChunkedDocument> captor = ArgumentCaptor.forClass(ChunkedDocument.class);

    verify(ingestionService).ingest(captor.capture());

    assertEquals(
        "product_1",
        captor.getValue().documentId());

    assertEquals(
        1,
        captor.getValue().chunks().size());
  }

  private ChunkedDocument chunkedDocument() {
    var revision = new ProcessedDocument.Revision(
        "10000000123",
        "A",
        "2026-01-01",
        2026);

    var chunk = new DocumentChunk(
        "product_1",
        "product_1.pdf",
        0,
        "Engine",
        List.of("G007144-0"),
        List.of(1),
        List.of("engine.type"),
        """
            Document: product_1
            Models: G007144-0
            Section: Engine

            [Page 1] Type of Engine: GENERAC G-FORCE 500 SERIES
            """);

    return new ChunkedDocument(
        "1",
        "1.3",
        "product_1",
        "product_1.pdf",
        revision,
        1,
        chunk.text().length(),
        List.of(chunk));
  }
}
