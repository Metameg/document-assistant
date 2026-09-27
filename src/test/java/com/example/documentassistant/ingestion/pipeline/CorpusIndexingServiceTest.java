package com.example.documentassistant.ingestion.pipeline;

import com.example.documentassistant.document.catalog.DocumentCatalogProperties;
import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.ingestion.chunking.DocumentChunkingService;
import com.example.documentassistant.ingestion.vector.ChunkVectorIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusIndexingServiceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void chunksValidatesAndIndexesCorpus()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve(
            "processed"));

    Path rawDirectory = Files.createDirectory(
        temporaryDirectory.resolve(
            "raw"));

    Files.writeString(
        processedDirectory.resolve(
            "product_1.json"),
        "{}");

    DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);

    ChunkVectorIngestionService ingestionService = mock(
        ChunkVectorIngestionService.class);

    ChunkedDocument chunkedDocument = chunkedDocument();

    when(chunkingService.chunkFile(
        any(Path.class)))
        .thenReturn(chunkedDocument);

    when(ingestionService.ingest(
        chunkedDocument))
        .thenReturn(
            new ChunkVectorIngestionService.IngestionResult(
                "product_1",
                1));

    IndexingJobRegistry registry = new IndexingJobRegistry();

    Executor sameThreadExecutor = Runnable::run;

    CorpusIndexingService service = new CorpusIndexingService(
        chunkingService,
        ingestionService,
        new DocumentCatalogProperties(
            processedDirectory,
            rawDirectory),
        registry,
        sameThreadExecutor);

    IndexingJobStatus status = service.startIndexing();

    assertEquals(
        IndexingJobStage.COMPLETED,
        status.stage());

    assertTrue(status.terminal());

    assertEquals(
        1,
        status.documentsDiscovered());

    assertEquals(
        1,
        status.documentsChunked());

    assertEquals(
        1,
        status.totalChunksGenerated());

    assertEquals(
        1,
        status.documentsIndexed());

    assertEquals(
        1,
        status.chunksStored());

    assertNull(status.errorMessage());

    verify(chunkingService).chunkFile(
        processedDirectory.resolve(
            "product_1.json"));

    verify(ingestionService).ingest(
        chunkedDocument);
  }

  @Test
  void recordsFailureAndReleasesJobSlot()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve(
            "processed"));

    Path rawDirectory = Files.createDirectory(
        temporaryDirectory.resolve(
            "raw"));

    Files.writeString(
        processedDirectory.resolve(
            "bad.json"),
        "{}");

    DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);

    when(chunkingService.chunkFile(
        any(Path.class)))
        .thenThrow(
            new IOException(
                "Invalid processed document"));

    IndexingJobRegistry registry = new IndexingJobRegistry();

    CorpusIndexingService service = new CorpusIndexingService(
        chunkingService,
        mock(
            ChunkVectorIngestionService.class),
        new DocumentCatalogProperties(
            processedDirectory,
            rawDirectory),
        registry,
        Runnable::run);

    IndexingJobStatus failed = service.startIndexing();

    assertEquals(
        IndexingJobStage.FAILED,
        failed.stage());

    assertTrue(failed.terminal());

    assertTrue(
        failed.errorMessage().contains(
            "Invalid processed document"));

    assertTrue(
        registry.getActiveJob().isEmpty());

    IndexingJobStatus next = registry.createJob();

    assertEquals(
        IndexingJobStage.IDLE,
        next.stage());
  }

  private ChunkedDocument chunkedDocument() {
    DocumentChunk chunk = new DocumentChunk(
        "product_1",
        "product_1.pdf",
        0,
        "Engine",
        List.of("G007144-0"),
        List.of(3),
        List.of("p3.engine.type"),
        "Type of Engine: "
            + "GENERAC G-FORCE 500 SERIES");

    return new ChunkedDocument(
        "1",
        "1.3",
        "product_1",
        "product_1.pdf",
        null,
        1,
        chunk.text().length(),
        List.of(chunk));
  }
}
