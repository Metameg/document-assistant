package com.example.documentassistant.ingestion.pipeline;

import java.time.Instant;
import java.util.UUID;

public record IndexingJobStatus(
    UUID jobId,
    long sequence,
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
}
