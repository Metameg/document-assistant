package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class VectorIngestionRunner implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(VectorIngestionRunner.class);

  private final ObjectMapper objectMapper;
  private final ChunkVectorIngestionService ingestionService;
  private final VectorIngestionProperties properties;

  public VectorIngestionRunner(
      ObjectMapper objectMapper,
      ChunkVectorIngestionService ingestionService,
      VectorIngestionProperties properties) {
    this.objectMapper = objectMapper;
    this.ingestionService = ingestionService;
    this.properties = properties;
  }

  @Override
  public void run(String... args) throws Exception {
    Path inputDirectory = properties.inputDirectory().toAbsolutePath().normalize();

    validateInputDirectory(inputDirectory);

    List<Path> jsonFiles = discoverJsonFiles(inputDirectory);

    if (jsonFiles.isEmpty()) {
      throw new IllegalStateException(
          "No JSON files found in " + inputDirectory);
    }

    log.info(
        "Discovered {} JSON files in {}",
        jsonFiles.size(),
        inputDirectory);

    Set<String> documentIds = new HashSet<>();

    int ingestedDocuments = 0;
    int ingestedChunks = 0;
    int skippedFiles = 0;

    for (Path jsonFile : jsonFiles) {
      JsonNode root = objectMapper.readTree(jsonFile.toFile());

      // The directory may also contain a batch-result JSON file.
      // Only files with the ChunkedDocument structure are ingested.
      if (!isChunkedDocument(root)) {
        log.info(
            "Skipping non-chunk document file: {}",
            jsonFile.getFileName());

        skippedFiles++;
        continue;
      }

      ChunkedDocument chunkedDocument = objectMapper.treeToValue(root, ChunkedDocument.class);

      if (!documentIds.add(chunkedDocument.documentId())) {
        throw new IllegalStateException(
            "Duplicate documentId found in chunk input: "
                + chunkedDocument.documentId());
      }

      log.info(
          "Ingesting document {} with {} chunks from {}",
          chunkedDocument.documentId(),
          chunkedDocument.chunks().size(),
          jsonFile.getFileName());

      /*
       * This call performs the important work:
       *
       * 1. ChunkDocumentMapper creates Spring AI Documents.
       * 2. Existing rows for this document are deleted.
       * 3. VectorStore.add(...) sends text to OpenRouter.
       * 4. OpenRouter returns embedding vectors.
       * 5. PgVectorStore inserts the text, metadata, and vectors.
       */
      ingestionService.ingest(chunkedDocument);

      ingestedDocuments++;
      ingestedChunks += chunkedDocument.chunks().size();

      log.info(
          "Finished document {}",
          chunkedDocument.documentId());
    }

    if (ingestedDocuments == 0) {
      throw new IllegalStateException(
          "JSON files were found, but none contained chunked documents");
    }

    log.info(
        "Vector ingestion complete: documents={}, chunks={}, skippedFiles={}",
        ingestedDocuments,
        ingestedChunks,
        skippedFiles);
  }

  private void validateInputDirectory(Path inputDirectory) {
    if (!Files.exists(inputDirectory)) {
      throw new IllegalStateException(
          "Chunk input directory does not exist: " + inputDirectory);
    }

    if (!Files.isDirectory(inputDirectory)) {
      throw new IllegalStateException(
          "Chunk input path is not a directory: " + inputDirectory);
    }

    if (!Files.isReadable(inputDirectory)) {
      throw new IllegalStateException(
          "Chunk input directory is not readable: " + inputDirectory);
    }
  }

  private List<Path> discoverJsonFiles(Path inputDirectory)
      throws IOException {

    try (Stream<Path> paths = Files.list(inputDirectory)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(this::hasJsonExtension)
          .sorted()
          .toList();
    }
  }

  private boolean hasJsonExtension(Path path) {
    return path.getFileName()
        .toString()
        .toLowerCase()
        .endsWith(".json");
  }

  private boolean isChunkedDocument(JsonNode root) {
    return root != null
        && root.isObject()
        && root.hasNonNull("documentId")
        && root.has("chunks")
        && root.get("chunks").isArray();
  }
}
