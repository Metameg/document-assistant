package com.example.documentassistant.document.catalog;

public class DocumentNotFoundException
    extends RuntimeException {

  public DocumentNotFoundException(
      String documentId) {

    super(
        "No available PDF was found for document: "
            + documentId);
  }
}
