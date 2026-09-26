package com.example.documentassistant.retrieval;

import java.util.List;

/**
 * One vector-search match with the metadata required to display
 * and verify its citation.
 */
public record RetrievedChunk(
    double similarityScore,
    String documentId,
    String sourceFile,
    String revisionPartNumber,
    String revision,
    String publicationDate,
    Integer copyrightYear,
    int chunkIndex,
    String section,
    List<String> modelNumbers,
    List<Integer> pageNumbers,
    List<String> sourceElementIds,
    String text) {

  public RetrievedChunk {
    modelNumbers = List.copyOf(modelNumbers);
    pageNumbers = List.copyOf(pageNumbers);
    sourceElementIds = List.copyOf(sourceElementIds);
  }
}
