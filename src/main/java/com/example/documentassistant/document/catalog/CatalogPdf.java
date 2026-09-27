package com.example.documentassistant.document.catalog;

import org.springframework.core.io.Resource;

import java.util.Objects;

public record CatalogPdf(
    String sourceFile,
    Resource resource,
    long contentLength) {

  public CatalogPdf {
    if (sourceFile == null || sourceFile.isBlank()) {
      throw new IllegalArgumentException(
          "sourceFile must not be blank");
    }

    Objects.requireNonNull(
        resource,
        "resource must not be null");

    if (contentLength < 0) {
      throw new IllegalArgumentException(
          "contentLength must not be negative");
    }
  }
}
