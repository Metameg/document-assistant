package com.example.documentassistant.web.demo;

import com.example.documentassistant.ingestion.pipeline.IndexingJobStage;
import com.example.documentassistant.ingestion.pipeline.IndexingJobStatus;

import java.time.Instant;
import java.util.UUID;

public record IndexingProgressEvent(
    UUID jobId,
    long sequence,
    String eventType,
    IndexingJobStage stage,
    boolean terminal,
    int documentsDiscovered,
    int documentsChunked,
    int totalChunksGenerated,
    int documentsIndexed,
    int chunksStored,
    String currentDocument,
    String errorMessage,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {

  public static IndexingProgressEvent from(
      IndexingJobStatus status) {

    String eventType = switch (status.stage()) {
      case COMPLETED -> "completed";
      case FAILED -> "failed";
      default -> "progress";
    };

    return new IndexingProgressEvent(
        status.jobId(),
        status.sequence(),
        eventType,
        status.stage(),
        status.terminal(),
        status.documentsDiscovered(),
        status.documentsChunked(),
        status.totalChunksGenerated(),
        status.documentsIndexed(),
        status.chunksStored(),
        status.currentDocument(),
        status.errorMessage(),
        status.createdAt(),
        status.startedAt(),
        status.finishedAt());
  }
}
