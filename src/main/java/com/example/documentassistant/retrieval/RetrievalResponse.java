package com.example.documentassistant.retrieval;

import java.util.List;

/**
 * The complete result of one semantic search.
 */
public record RetrievalResponse(
    String query,
    int topK,
    double similarityThreshold,
    int resultCount,
    List<RetrievedChunk> results) {

  public RetrievalResponse {
    results = List.copyOf(results);

    if (resultCount != results.size()) {
      throw new IllegalArgumentException(
          "resultCount must match results size");
    }
  }
}
