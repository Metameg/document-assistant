package com.example.documentassistant.document.model;

import java.util.List;

/**
 * One processed document's complete semantic-chunk output.
 *
 * <p>
 * This is the JSON contract between offline chunk generation and
 * offline vector ingestion.
 * </p>
 */
public record ChunkedDocument(
    String chunkSchemaVersion,
    String sourceSchemaVersion,
    String documentId,
    String sourceFile,
    ProcessedDocument.Revision revision,
    int chunkCount,
    int longestChunkCharacters,
    List<DocumentChunk> chunks) {

  public ChunkedDocument {
    chunks = List.copyOf(chunks);
  }
}
