package com.example.documentassistant.ingestion.pipeline;

import java.util.UUID;

public class IndexingJobNotFoundException
    extends RuntimeException {

  public IndexingJobNotFoundException(
      UUID jobId) {

    super(
        "Indexing job was not found: "
            + jobId);
  }
}
