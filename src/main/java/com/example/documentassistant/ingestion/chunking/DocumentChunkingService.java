package com.example.documentassistant.ingestion.chunking;

import com.example.documentassistant.document.chunking.SpecSheetChunker;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import com.example.documentassistant.document.model.ChunkedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Batch operations: load processed JSON, chunk it, and export a batch. */
public class DocumentChunkingService {
  private static final Logger log = LoggerFactory.getLogger(DocumentChunkingService.class);

  private final JsonMapper mapper;
  private final SpecSheetChunker chunker = new SpecSheetChunker();

  public DocumentChunkingService(JsonMapper applicationMapper) {
    // Derive a strict mapper without changing Spring's API response configuration.
    this.mapper = applicationMapper.rebuild()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
        .build();
  }

  /**
   * Also reusable later by embedding/indexing code, without writing preview
   * files.
   */
  public ChunkedDocument chunkFile(Path inputFile) throws IOException {
    ProcessedDocument document = mapper.readValue(Files.readString(inputFile), ProcessedDocument.class);
    if (document.documentId() == null
        || !document.documentId().matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("documentId must be a safe, nonempty file identifier");
    }
    if (document.sourceFile() == null || document.sourceFile().isBlank()) {
      throw new IllegalArgumentException("Missing sourceFile in " + inputFile);
    }
    List<DocumentChunk> chunks = chunker.chunk(document);
    if (chunks.isEmpty()) {
      throw new IllegalArgumentException("Document produced no chunks: " + document.documentId());
    }
    int longest = chunks.stream().mapToInt(chunk -> chunk.text().length()).max().orElse(0);
    return new ChunkedDocument("1", document.schemaVersion(), document.documentId(),
        document.sourceFile(), document.revision(), chunks.size(), longest, chunks);
  }

  /**
   * Processes every regular .json file directly inside inputDirectory.
   * Each run uses a new folder: old successful output cannot be confused with a
   * failed or changed document in this run. The report lists exactly what
   * succeeded.
   */
  public BatchResult chunkDirectory(Path inputDirectory, Path outputRoot) throws IOException {
    Path input = inputDirectory.toAbsolutePath().normalize();
    Path output = outputRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(input)) {
      throw new IllegalArgumentException("Processed JSON directory does not exist: " + input);
    }
    List<Path> files;
    try (var stream = Files.list(input)) {
      files = stream.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
          .sorted().toList();
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("No processed JSON files found in " + input);
    }

    Files.createDirectories(output);
    Path runDirectory = Files.createTempDirectory(output, "run-");
    List<DocumentResult> successes = new ArrayList<>();
    List<DocumentFailure> failures = new ArrayList<>();
    Set<String> documentIds = new HashSet<>();

    for (Path file : files) {
      try {
        ChunkedDocument document = chunkFile(file);
        if (!documentIds.add(document.documentId())) {
          throw new IllegalArgumentException("Duplicate documentId in this batch: " + document.documentId());
        }
        Path destination = runDirectory.resolve(document.documentId() + "-chunks.json");
        writeJsonAtomically(destination, document);
        successes.add(new DocumentResult(file.toString(), document.documentId(),
            document.chunkCount(), document.longestChunkCharacters(), destination.toString()));
        log.info("Chunked {}: {} chunks, longest {} characters", file.getFileName(),
            document.chunkCount(), document.longestChunkCharacters());
      } catch (IOException | RuntimeException exception) {
        String reason = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        failures.add(new DocumentFailure(file.toString(), reason));
        log.error("Could not chunk {}: {}", file.getFileName(), reason);
      }
    }

    int totalChunks = successes.stream().mapToInt(DocumentResult::chunkCount).sum();
    BatchResult result = new BatchResult(files.size(), successes.size(), failures.size(),
        totalChunks, failures.isEmpty(), runDirectory.toString(),
        List.copyOf(successes), List.copyOf(failures));
    writeJsonAtomically(runDirectory.resolve("batch-result.json"), result);
    return result;
  }

  private void writeJsonAtomically(Path destination, Object value) throws IOException {
    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    Path temporary = Files.createTempFile(destination.getParent(), "chunk-write-", ".tmp");
    try {
      Files.writeString(temporary, json);
      try {
        Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
        Files.move(temporary, destination);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public record DocumentResult(
      String inputFile, String documentId, int chunkCount,
      int longestChunkCharacters, String outputFile) {
  }

  public record DocumentFailure(String inputFile, String reason) {
  }

  public record BatchResult(
      int discoveredFiles, int successfulDocuments, int failedDocuments,
      int totalChunks, boolean complete, String outputDirectory,
      List<DocumentResult> documents, List<DocumentFailure> failures) {
  }
}
