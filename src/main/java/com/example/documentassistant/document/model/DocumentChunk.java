package com.example.documentassistant.document.model;

import java.util.List;

/**
 * Retrieval text together with the source information needed for citations.
 * Chunk indexes are zero-based within a document; PDF page numbers are one-based.
 * Source element IDs are scoped to documentId.
 */
public record DocumentChunk(
    String documentId,
    String sourceFile,
    int chunkIndex,
    String section,
    List<String> modelNumbers,
    List<Integer> pageNumbers,
    List<String> sourceElementIds,
    String text) {

  public DocumentChunk {
    modelNumbers = List.copyOf(modelNumbers);
    pageNumbers = List.copyOf(pageNumbers);
    sourceElementIds = List.copyOf(sourceElementIds);
  }

  /**
   * Temporary compatibility with the existing HTML DocumentChunker.
   * These legacy chunks have no source metadata and are not suitable for
   * the new PDF citation workflow.
   */
  public DocumentChunk(int chunkIndex, String text) {
    this(null, null, chunkIndex, null, List.of(), List.of(), List.of(), text);
  }
}
