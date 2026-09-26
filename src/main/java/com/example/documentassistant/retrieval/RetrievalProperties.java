package com.example.documentassistant.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration controlling semantic retrieval.
 */
@ConfigurationProperties(prefix = "document-assistant.retrieval")
public record RetrievalProperties(
    @DefaultValue("5") int defaultTopK,
    @DefaultValue("20") int maxTopK,
    @DefaultValue("0.0") double similarityThreshold) {

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
  }
}
