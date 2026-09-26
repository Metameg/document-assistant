package com.example.documentassistant.retrieval;

/**
 * A natural-language semantic-search request.
 *
 * @param query the user's natural-language question
 * @param topK  optional number of results; null uses the configured default
 */
public record RetrievalRequest(
    String query,
    Integer topK) {

  public RetrievalRequest {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException(
          "query must not be blank");
    }

    query = query.trim();

    if (topK != null && topK < 1) {
      throw new IllegalArgumentException(
          "topK must be at least 1");
    }
  }
}
