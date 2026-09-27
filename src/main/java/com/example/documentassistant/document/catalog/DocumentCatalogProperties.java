package com.example.documentassistant.document.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties(prefix = "document-assistant.documents")
public record DocumentCatalogProperties(
    @DefaultValue("data/processed/specsheets") Path processedDirectory,

    @DefaultValue("data/raw/specsheets") Path rawPdfDirectory) {

  public DocumentCatalogProperties {
    Objects.requireNonNull(
        processedDirectory,
        "processedDirectory must not be null");

    Objects.requireNonNull(
        rawPdfDirectory,
        "rawPdfDirectory must not be null");
  }
}
