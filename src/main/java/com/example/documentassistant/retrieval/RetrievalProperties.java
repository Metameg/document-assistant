package com.example.documentassistant.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration controlling semantic retrieval.
 */
@ConfigurationProperties(prefix = "document-assistant.retrieval")
public record RetrievalProperties(
    @DefaultValue("5") int defaultTopK,
    @DefaultValue("30") int maxTopK,
    @DefaultValue("0.0") double similarityThreshold,
    @DefaultValue("60") int keywordLimit,
    @DefaultValue("12") int diversityDocumentLimit,
    @DefaultValue("2") int diversityChunksPerDocument,
    @DefaultValue("40") int candidateLimit,
    @DefaultValue("30") int defaultEvidenceLimit,
    @DefaultValue("40") int maxEvidenceLimit,
    @DefaultValue("70000") int maxContextCharacters) {

  public RetrievalProperties {
    if (defaultTopK < 1) {
      throw new IllegalArgumentException(
          "defaultTopK must be at least 1");
    }

    if (maxTopK < defaultTopK) {
      throw new IllegalArgumentException(
          "maxTopK must be at least defaultTopK");
    }

    if (similarityThreshold < 0.0
        || similarityThreshold > 1.0) {

      throw new IllegalArgumentException(
          "similarityThreshold must be between 0.0 and 1.0");
    }

    if (keywordLimit < 1 || keywordLimit > 100) {
      throw new IllegalArgumentException(
          "keywordLimit must be between 1 and 100");
    }

    if (diversityDocumentLimit < 0 || diversityDocumentLimit > 100
        || diversityChunksPerDocument < 1
        || diversityChunksPerDocument > 100) {
      throw new IllegalArgumentException(
          "diversityDocumentLimit must be between 0 and 100, and "
              + "diversityChunksPerDocument must be between 1 and 100");
    }

    if (candidateLimit < 1
        || defaultEvidenceLimit < 1
        || maxEvidenceLimit < defaultEvidenceLimit
        || maxEvidenceLimit > candidateLimit) {
      throw new IllegalArgumentException(
          "Require 1 <= defaultEvidenceLimit <= maxEvidenceLimit"
              + " <= candidateLimit");
    }

    if (maxContextCharacters < 1) {
      throw new IllegalArgumentException(
          "maxContextCharacters must be positive");
    }
  }
}
