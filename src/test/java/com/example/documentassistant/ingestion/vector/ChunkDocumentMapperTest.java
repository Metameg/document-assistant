package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDocumentMapperTest {

  private final ChunkDocumentMapper mapper = new ChunkDocumentMapper();

  @Test
  void mapsChunkTextIdentityAndCitationMetadata() {
    ChunkedDocument source = document("A");

    var documents = mapper.toDocuments(source);

    assertEquals(1, documents.size());

    var document = documents.getFirst();

    assertEquals(
        source.chunks().getFirst().text(),
        document.getText());

    assertDoesNotThrow(
        () -> UUID.fromString(document.getId()));

    assertEquals(
        "product_1",
        document.getMetadata().get("documentId"));

    assertEquals(
        "product_1.pdf",
        document.getMetadata().get("sourceFile"));

    assertEquals(
        0,
        document.getMetadata().get("chunkIndex"));

    assertEquals(
        "Engine",
        document.getMetadata().get("section"));

    assertEquals(
        List.of("G007144-0"),
        document.getMetadata().get("modelNumbers"));

    assertEquals(
        List.of(1),
        document.getMetadata().get("pageNumbers"));

    assertEquals(
        List.of("engine.type"),
        document.getMetadata().get("sourceElementIds"));

    assertEquals(
        "A",
        document.getMetadata().get("revision"));
  }

  @Test
  void producesTheSameIdForTheSameChunk() {
    ChunkedDocument source = document("A");

    String firstId = mapper.toDocuments(source).getFirst().getId();

    String secondId = mapper.toDocuments(source).getFirst().getId();

    assertEquals(firstId, secondId);
  }

  @Test
  void producesDifferentIdsForDifferentRevisions() {
    String revisionAId = mapper.toDocuments(document("A"))
        .getFirst()
        .getId();

    String revisionBId = mapper.toDocuments(document("B"))
        .getFirst()
        .getId();

    assertNotEquals(revisionAId, revisionBId);
  }

  @Test
  void rejectsAnIncorrectChunkCount() {
    ChunkedDocument valid = document("A");

    ChunkedDocument invalid = new ChunkedDocument(
        valid.chunkSchemaVersion(),
        valid.sourceSchemaVersion(),
        valid.documentId(),
        valid.sourceFile(),
        valid.revision(),
        2,
        valid.longestChunkCharacters(),
        valid.chunks());

    var exception = assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toDocuments(invalid));

    assertTrue(
        exception.getMessage().contains("chunkCount"));
  }

  private ChunkedDocument document(String revisionName) {
    var revision = new ProcessedDocument.Revision(
        "10000000123",
        revisionName,
        "2026-01-01",
        2026);

    var chunk = new DocumentChunk(
        "product_1",
        "product_1.pdf",
        0,
        "Engine",
        List.of("G007144-0"),
        List.of(1),
        List.of("engine.type"),
        """
            Document: product_1
            Models: G007144-0
            Section: Engine

            [Page 1] Type of Engine: GENERAC G-FORCE 500 SERIES
            """);

    return new ChunkedDocument(
        "1",
        "1.3",
        "product_1",
        "product_1.pdf",
        revision,
        1,
        chunk.text().length(),
        List.of(chunk));
  }
}
