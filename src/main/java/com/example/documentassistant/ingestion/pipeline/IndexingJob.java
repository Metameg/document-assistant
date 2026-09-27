package com.example.documentassistant.ingestion.pipeline;

import java.time.Instant;
import java.util.UUID;

public final class IndexingJob {

  private final UUID jobId;
  private final Instant createdAt;

  private long sequence;
  private IndexingJobStage stage;

  private int documentsDiscovered;
  private int documentsChunked;
  private int totalChunksGenerated;
  private int documentsIndexed;
  private int chunksStored;

  private String currentDocument;
  private String errorMessage;

  private Instant startedAt;
  private Instant finishedAt;

  IndexingJob(UUID jobId) {
    this.jobId = jobId;
    this.createdAt = Instant.now();
    this.stage = IndexingJobStage.IDLE;
  }

  synchronized void start() {
    requireNotTerminal();

    startedAt = Instant.now();
    stage = IndexingJobStage.DISCOVERING_DOCUMENTS;

    advanceSequence();
  }

  synchronized void documentsDiscovered(
      int count) {

    requireNotTerminal();

    if (count < 0) {
      throw new IllegalArgumentException(
          "Document count must not be negative");
    }

    stage = IndexingJobStage.DISCOVERING_DOCUMENTS;

    documentsDiscovered = count;
    currentDocument = null;

    advanceSequence();
  }

  synchronized void startChunking(
      String documentName) {

    requireNotTerminal();

    stage = IndexingJobStage.CHUNKING;
    currentDocument = documentName;

    advanceSequence();
  }

  synchronized void documentChunked(
      String documentName,
      int generatedChunks) {

    requireNotTerminal();

    if (generatedChunks < 1) {
      throw new IllegalArgumentException(
          "A chunked document must contain "
              + "at least one chunk");
    }

    stage = IndexingJobStage.CHUNKING;
    currentDocument = documentName;

    documentsChunked++;
    totalChunksGenerated += generatedChunks;

    advanceSequence();
  }

  synchronized void startValidation() {
    requireNotTerminal();

    stage = IndexingJobStage.VALIDATING_CHUNKS;

    currentDocument = null;

    advanceSequence();
  }

  synchronized void startEmbedding(
      String documentId) {

    requireNotTerminal();

    stage = IndexingJobStage.EMBEDDING_AND_STORING;

    currentDocument = documentId;

    advanceSequence();
  }

  synchronized void documentIndexed(
      String documentId,
      int storedChunks) {

    requireNotTerminal();

    if (storedChunks < 1) {
      throw new IllegalArgumentException(
          "An indexed document must store "
              + "at least one chunk");
    }

    stage = IndexingJobStage.EMBEDDING_AND_STORING;

    currentDocument = documentId;

    documentsIndexed++;
    chunksStored += storedChunks;

    advanceSequence();
  }

  synchronized void complete() {
    requireNotTerminal();

    stage = IndexingJobStage.COMPLETED;
    currentDocument = null;
    finishedAt = Instant.now();

    advanceSequence();
  }

  synchronized void fail(
      String message) {

    if (isTerminal()) {
      return;
    }

    stage = IndexingJobStage.FAILED;
    currentDocument = null;

    errorMessage = message == null || message.isBlank()
        ? "Indexing failed"
        : message;

    finishedAt = Instant.now();

    advanceSequence();
  }

  public synchronized IndexingJobStatus snapshot() {
    return new IndexingJobStatus(
        jobId,
        sequence,
        stage,
        isTerminal(),
        documentsDiscovered,
        documentsChunked,
        totalChunksGenerated,
        documentsIndexed,
        chunksStored,
        currentDocument,
        errorMessage,
        createdAt,
        startedAt,
        finishedAt);
  }

  private boolean isTerminal() {
    return stage == IndexingJobStage.COMPLETED
        || stage == IndexingJobStage.FAILED;
  }

  private void requireNotTerminal() {
    if (isTerminal()) {
      throw new IllegalStateException(
          "Indexing job is already terminal: "
              + jobId);
    }
  }

  private void advanceSequence() {
    sequence++;
  }
}
