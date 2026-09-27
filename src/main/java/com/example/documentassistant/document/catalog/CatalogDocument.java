package com.example.documentassistant.document.catalog;

import com.example.documentassistant.document.model.ProcessedDocument;

import java.util.List;

public record CatalogDocument(
    String documentId,
    String sourceFile,
    int pageCount,
    List<String> modelNumbers,
    ProcessedDocument.Revision revision,
    boolean pdfAvailable) {

  public CatalogDocument {
    modelNumbers = List.copyOf(modelNumbers);
  }
}
