package com.example.documentassistant.ingestion.pipeline;

import java.util.UUID;

public class IndexingJobAlreadyRunningException
    extends RuntimeException {

  private final UUID activeJobId;

  public IndexingJobAlreadyRunningException(
      UUID activeJobId) {

    super(
        "An indexing job is already running: "
            + activeJobId);

    this.activeJobId = activeJobId;
  }

  public UUID activeJobId() {
    return activeJobId;
  }
}
