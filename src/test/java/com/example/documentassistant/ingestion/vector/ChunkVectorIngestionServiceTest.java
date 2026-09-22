package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ChunkVectorIngestionServiceTest {

  @Test
  void replacesStoredDocumentAndAddsMappedChunks() {
    VectorStore vectorStore = mock(VectorStore.class);

    var service = new ChunkVectorIngestionService(
        new ChunkDocumentMapper(),
        vectorStore);

    var result = service.ingest(chunkedDocument());

    assertEquals("product_1", result.documentId());
    assertEquals(1, result.storedChunks());

    InOrder calls = inOrder(vectorStore);

    calls.verify(vectorStore).delete(
        any(Filter.Expression.class));

    calls.verify(vectorStore).add(
        argThat(documents -> documents.size() == 1
            && documents.getFirst().getText()
                .contains("GENERAC G-FORCE 500 SERIES")
            && documents.getFirst().getMetadata()
                .get("documentId")
                .equals("product_1")));
  }

  private ChunkedDocument chunkedDocument() {
    var revision = new ProcessedDocument.Revision(
        "10000000123",
        "A",
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
