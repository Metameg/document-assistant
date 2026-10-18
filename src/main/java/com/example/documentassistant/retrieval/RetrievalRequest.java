package com.example.documentassistant.retrieval;

/**
 * A natural-language semantic-search request.
 *
 * @param query the user's natural-language question
 * @param topK  optional number of results; null uses the configured default
 */
public record RetrievalRequest(
    String query,
    Integer topK,
    String corpusId,
    String versionId) {

  public RetrievalRequest(String query, Integer topK) { this(query, topK, null, null); }
  public RetrievalRequest(String query, Integer topK, String corpusId) { this(query, topK, corpusId, null); }

  public RetrievalRequest {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException(
          "query must not be blank");
    }

    query = query.trim();
    corpusId = corpusId == null ? "specifications" : corpusId;
    if (!"specifications".equals(corpusId)) {
      try { java.util.UUID.fromString(corpusId); }
      catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid corpusId"); }
    } else if (versionId != null) {
      throw new IllegalArgumentException("The specification corpus does not support version selection");
    }
    if (versionId != null) java.util.UUID.fromString(versionId);

    if (topK != null && topK < 1) {
      throw new IllegalArgumentException(
          "topK must be at least 1");
    }
  }
}
