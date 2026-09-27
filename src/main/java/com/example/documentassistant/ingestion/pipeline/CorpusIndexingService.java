package com.example.documentassistant.ingestion.pipeline;

import com.example.documentassistant.document.catalog.DocumentCatalogProperties;
import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.ingestion.chunking.DocumentChunkingService;
import com.example.documentassistant.ingestion.vector.ChunkVectorIngestionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class CorpusIndexingService {

  private final DocumentChunkingService chunkingService;
  private final ChunkVectorIngestionService ingestionService;
  private final DocumentCatalogProperties catalogProperties;
  private final IndexingJobRegistry jobRegistry;
  private final Executor executor;

  public CorpusIndexingService(
      DocumentChunkingService chunkingService,
      ChunkVectorIngestionService ingestionService,
      DocumentCatalogProperties catalogProperties,
      IndexingJobRegistry jobRegistry,
      @Qualifier("corpusIndexingExecutor") Executor executor) {

    this.chunkingService = chunkingService;
    this.ingestionService = ingestionService;
    this.catalogProperties = catalogProperties;
    this.jobRegistry = jobRegistry;
    this.executor = executor;
  }

  public IndexingJobStatus startIndexing() {
    IndexingJobStatus created = jobRegistry.createJob();

    try {
      executor.execute(
          () -> runJob(created.jobId()));

    } catch (RuntimeException exception) {
      jobRegistry.update(
          created.jobId(),
          job -> job.fail(
              failureMessage(exception)));

      throw exception;
    }

    return jobRegistry.getStatus(
        created.jobId());
  }

  private void runJob(UUID jobId) {
    try {
      jobRegistry.update(
          jobId,
          IndexingJob::start);

      List<Path> inputFiles = discoverProcessedDocuments();

      jobRegistry.update(
          jobId,
          job -> job.documentsDiscovered(
              inputFiles.size()));

      List<ChunkedDocument> chunkedDocuments = new ArrayList<>();

      for (Path inputFile : inputFiles) {
        String filename = inputFile.getFileName().toString();

        jobRegistry.update(
            jobId,
            job -> job.startChunking(
                filename));

        ChunkedDocument chunkedDocument = chunkingService.chunkFile(
            inputFile);

        validateChunkedDocument(
            chunkedDocument);

        chunkedDocuments.add(
            chunkedDocument);

        jobRegistry.update(
            jobId,
            job -> job.documentChunked(
                chunkedDocument.documentId(),
                chunkedDocument.chunkCount()));
      }

      jobRegistry.update(
          jobId,
          IndexingJob::startValidation);

      validateCompleteBatch(
          chunkedDocuments);

      for (ChunkedDocument chunkedDocument : chunkedDocuments) {
        jobRegistry.update(
            jobId,
            job -> job.startEmbedding(
                chunkedDocument.documentId()));

        var result = ingestionService.ingest(
            chunkedDocument);

        if (!Objects.equals(
            result.documentId(),
            chunkedDocument.documentId())
            || result.storedChunks() != chunkedDocument.chunkCount()) {
          throw new IllegalStateException(
              "Vector ingestion result did not "
                  + "match chunked document: "
                  + chunkedDocument.documentId());
        }

        jobRegistry.update(
            jobId,
            job -> job.documentIndexed(
                result.documentId(),
                result.storedChunks()));
      }

      jobRegistry.update(
          jobId,
          IndexingJob::complete);

    } catch (Exception exception) {
      jobRegistry.update(
          jobId,
          job -> job.fail(
              failureMessage(exception)));
    }
  }

  private List<Path> discoverProcessedDocuments()
      throws IOException {

    Path inputDirectory = catalogProperties
        .processedDirectory()
        .toAbsolutePath()
        .normalize();

    if (!Files.isDirectory(inputDirectory)) {
      throw new IllegalStateException(
          "Processed document directory "
              + "does not exist: "
              + inputDirectory);
    }

    List<Path> files;

    try (var paths = Files.list(inputDirectory)) {

      files = paths
          .filter(Files::isRegularFile)
          .filter(this::hasJsonExtension)
          .sorted()
          .toList();
    }

    if (files.isEmpty()) {
      throw new IllegalStateException(
          "No processed JSON documents "
              + "were found in "
              + inputDirectory);
    }

    return files;
  }

  private boolean hasJsonExtension(
      Path path) {

    return path
        .getFileName()
        .toString()
        .toLowerCase(Locale.ROOT)
        .endsWith(".json");
  }

  private void validateCompleteBatch(
      List<ChunkedDocument> documents) {

    if (documents.isEmpty()) {
      throw new IllegalStateException(
          "Chunking produced no documents");
    }

    Set<String> documentIds = new HashSet<>();

    int totalChunks = 0;

    for (ChunkedDocument document : documents) {
      if (!documentIds.add(
          document.documentId())) {
        throw new IllegalStateException(
            "Duplicate documentId in "
                + "chunk batch: "
                + document.documentId());
      }

      totalChunks += document.chunkCount();
    }

    if (totalChunks < 1) {
      throw new IllegalStateException(
          "Chunking produced no chunks");
    }
  }

  private void validateChunkedDocument(
      ChunkedDocument document) {

    if (document == null) {
      throw new IllegalStateException(
          "Chunking returned a null document");
    }

    if (document.documentId() == null
        || document.documentId().isBlank()) {
      throw new IllegalStateException(
          "Chunked document has no documentId");
    }

    if (document.chunks() == null
        || document.chunks().isEmpty()) {
      throw new IllegalStateException(
          "Chunked document has no chunks: "
              + document.documentId());
    }

    if (document.chunkCount() != document.chunks().size()) {
      throw new IllegalStateException(
          "Chunk count does not match chunks "
              + "list: "
              + document.documentId());
    }

    for (int index = 0; index < document.chunks().size(); index++) {
      DocumentChunk chunk = document.chunks().get(index);

      if (chunk == null) {
        throw new IllegalStateException(
            "Chunk list contains null: "
                + document.documentId());
      }

      if (chunk.chunkIndex() != index) {
        throw new IllegalStateException(
            "Chunk indexes are not sequential: "
                + document.documentId());
      }

      if (!document.documentId().equals(
          chunk.documentId())) {
        throw new IllegalStateException(
            "Chunk documentId does not match "
                + "its parent: "
                + document.documentId());
      }

      if (!document.sourceFile().equals(
          chunk.sourceFile())) {
        throw new IllegalStateException(
            "Chunk sourceFile does not match "
                + "its parent: "
                + document.documentId());
      }

      if (chunk.text() == null
          || chunk.text().isBlank()) {
        throw new IllegalStateException(
            "Chunk text is blank: "
                + document.documentId()
                + ":"
                + index);
      }
    }
  }

  private String failureMessage(
      Exception exception) {

    Throwable root = exception;

    while (root.getCause() != null
        && root.getCause() != root) {
      root = root.getCause();
    }

    String message = root.getMessage();

    if (message == null || message.isBlank()) {
      return root
          .getClass()
          .getSimpleName();
    }

    return root
        .getClass()
        .getSimpleName()
        + ": "
        + message;
  }
}
