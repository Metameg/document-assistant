package com.example.documentassistant.ingestion.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties(prefix = "document-assistant.vector-ingestion")
public record VectorIngestionProperties(
    Path inputDirectory) {

  public VectorIngestionProperties {
    Objects.requireNonNull(
        inputDirectory,
        "document-assistant.vector-ingestion.input-directory must be configured");
  }
}
