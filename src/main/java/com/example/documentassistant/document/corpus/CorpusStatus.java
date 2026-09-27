package com.example.documentassistant.document.corpus;

import java.time.Instant;
import java.util.List;

public record CorpusStatus(
    boolean indexed,
    boolean inSync,
    int catalogDocumentCount,
    int indexedDocumentCount,
    int storedChunkCount,
    List<String> missingDocumentIds,
    List<String> unexpectedDocumentIds,
    Instant checkedAt) {

  public CorpusStatus {
    missingDocumentIds = List.copyOf(missingDocumentIds);

    unexpectedDocumentIds = List.copyOf(unexpectedDocumentIds);
  }
}
