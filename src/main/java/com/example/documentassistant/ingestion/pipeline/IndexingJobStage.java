package com.example.documentassistant.ingestion.pipeline;

public enum IndexingJobStage {
  IDLE,
  DISCOVERING_DOCUMENTS,
  CHUNKING,
  VALIDATING_CHUNKS,
  EMBEDDING_AND_STORING,
  COMPLETED,
  FAILED
}
