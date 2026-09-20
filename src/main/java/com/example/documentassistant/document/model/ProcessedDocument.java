package com.example.documentassistant.document.model;

import java.util.List;

/** The application-level JSON produced by the offline Python extraction. */
public record ProcessedDocument(
    String schemaVersion,
    String documentId,
    String sourceFile,
    String documentType,
    int pageCount,
    List<String> models,
    Revision revision,
    List<Section> sections,
    Extraction extraction) {

  public ProcessedDocument {
    if (!"1.3".equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
    }
    models = List.copyOf(models);
    sections = List.copyOf(sections);
  }

  public record Section(
      String title,
      int pageNumber,
      List<String> modelNumbers,
      List<String> sourceModelNumbers,
      List<DocumentElement> elements) {

    public Section {
      modelNumbers = List.copyOf(modelNumbers);
      sourceModelNumbers = List.copyOf(sourceModelNumbers);
      elements = List.copyOf(elements);
    }
  }

  public record Revision(
      String partNumber,
      String revision,
      String publicationDate,
      int copyrightYear) {
  }

  public record Extraction(
      String strategy,
      String doclingVersion,
      String pdfplumberVersion,
      List<String> warnings) {
  }
}
