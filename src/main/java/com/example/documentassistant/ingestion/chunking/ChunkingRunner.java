package com.example.documentassistant.ingestion.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.file.Path;

/** Registered only by the dedicated non-web ingestion configuration. */
@ConditionalOnProperty(prefix = "document-assistant.ingestion.chunking", name = "run-on-startup", havingValue = "true", matchIfMissing = false)

public class ChunkingRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ChunkingRunner.class);

  private final DocumentChunkingService service;
  private final Path inputDirectory;
  private final Path outputDirectory;

  public ChunkingRunner(
      DocumentChunkingService service,
      String inputDirectory,
      String outputDirectory) {
    this.service = service;
    this.inputDirectory = Path.of(inputDirectory);
    this.outputDirectory = Path.of(outputDirectory);
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    log.info("Starting chunking batch from {}", inputDirectory.toAbsolutePath());
    var result = service.chunkDirectory(inputDirectory, outputDirectory);
    log.info("Chunking batch: {} discovered, {} succeeded, {} failed, {} chunks. Output: {}",
        result.discoveredFiles(), result.successfulDocuments(), result.failedDocuments(),
        result.totalChunks(), result.outputDirectory());
    if (!result.complete()) {
      throw new IllegalStateException("Chunking batch incomplete. Inspect "
          + Path.of(result.outputDirectory(), "batch-result.json"));
    }
    // ChunkingApplication closes the context and exits after this returns.
  }
}
