package com.example.documentassistant.retrieval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkRetrievalServiceTest {

  @Test
  void searchesVectorStoreAndMapsScoreAndCitationMetadata() {
    VectorStore vectorStore = mock(VectorStore.class);

    when(vectorStore.similaritySearch(
        any(SearchRequest.class)))
        .thenReturn(List.of(searchResult()));

    var service = new ChunkRetrievalService(
        vectorStore,
        new RetrievalProperties(5, 20, 0.35));

    RetrievalResponse response = service.search(
        new RetrievalRequest(
            "  What engine does this model use?  ",
            3));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

    verify(vectorStore)
        .similaritySearch(captor.capture());

    assertEquals(
        "What engine does this model use?",
        captor.getValue().getQuery());

    assertEquals(
        3,
        captor.getValue().getTopK());

    assertEquals(
        0.35,
        captor.getValue().getSimilarityThreshold());

    assertEquals(
        "What engine does this model use?",
        response.query());

    assertEquals(3, response.topK());
    assertEquals(1, response.resultCount());

    RetrievedChunk result = response.results().getFirst();

    assertEquals(
        0.92,
        result.similarityScore());

    assertEquals(
        "product_1",
        result.documentId());

    assertEquals(
        "product_1.pdf",
        result.sourceFile());

    assertEquals(
        "10000000123",
        result.revisionPartNumber());

    assertEquals(
        "A",
        result.revision());

    assertEquals(
        "2026-01-01",
        result.publicationDate());

    assertEquals(
        2026,
        result.copyrightYear());

    assertEquals(
        7,
        result.chunkIndex());

    assertEquals(
        "Engine",
        result.section());

    assertEquals(
        List.of("G007144-0"),
        result.modelNumbers());

    assertEquals(
        List.of(1, 2),
        result.pageNumbers());

    assertEquals(
        List.of("engine.type", "engine.note"),
        result.sourceElementIds());

    assertEquals(
        "Type of Engine: GENERAC G-FORCE 500 SERIES",
        result.text());
  }

  @Test
  void usesConfiguredDefaultTopKAndReturnsEmptyResults() {
    VectorStore vectorStore = mock(VectorStore.class);

    when(vectorStore.similaritySearch(
        any(SearchRequest.class)))
        .thenReturn(List.of());

    var service = new ChunkRetrievalService(
        vectorStore,
        new RetrievalProperties(5, 20, 0.0));

    RetrievalResponse response = service.search(
        new RetrievalRequest(
            "generator fuel use",
            null));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

    verify(vectorStore)
        .similaritySearch(captor.capture());

    assertEquals(
        5,
        captor.getValue().getTopK());

    assertEquals(
        0,
        response.resultCount());

    assertEquals(
        List.of(),
        response.results());
  }

  @Test
  void rejectsTopKAboveConfiguredMaximum() {
    var service = new ChunkRetrievalService(
        mock(VectorStore.class),
        new RetrievalProperties(5, 10, 0.0));

    var exception = assertThrows(
        IllegalArgumentException.class,
        () -> service.search(
            new RetrievalRequest(
                "generator dimensions",
                11)));

    assertEquals(
        "topK must not exceed configured maximum of 10",
        exception.getMessage());
  }

  @Test
  void rejectsResultWithoutRequiredCitationMetadata() {
    VectorStore vectorStore = mock(VectorStore.class);

    Document invalid = Document.builder()
        .id("bad-result")
        .text("result text")
        .score(0.75)
        .metadata(Map.of(
            "documentId",
            "product_1"))
        .build();

    when(vectorStore.similaritySearch(
        any(SearchRequest.class)))
        .thenReturn(List.of(invalid));

    var service = new ChunkRetrievalService(
        vectorStore,
        new RetrievalProperties(5, 20, 0.0));

    var exception = assertThrows(
        IllegalStateException.class,
        () -> service.search(
            new RetrievalRequest(
                "engine",
                null)));

    assertEquals(
        "Vector store result has invalid metadata: sourceFile",
        exception.getMessage());
  }

  @Test
  void addsMetadataFilterWhenQueryContainsModelNumber() {
    VectorStore vectorStore = mock(VectorStore.class);

    when(vectorStore.similaritySearch(
        any(SearchRequest.class)))
        .thenReturn(List.of());

    var service = new ChunkRetrievalService(
        vectorStore,
        new RetrievalProperties(5, 20, 0.0));

    service.search(
        new RetrievalRequest(
            "What type of engine does model g007144-0 use?",
            5));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

    verify(vectorStore)
        .similaritySearch(captor.capture());

    SearchRequest searchRequest = captor.getValue();

    assertNotNull(
        searchRequest.getFilterExpression(),
        "A query containing a model number "
            + "should apply a metadata filter");
  }

  private Document searchResult() {
    return Document.builder()
        .id("28db06fd-e4d8-3b56-a53d-8f293143f694")
        .text(
            "Type of Engine: GENERAC G-FORCE 500 SERIES")
        .score(0.92)
        .metadata(Map.ofEntries(
            Map.entry(
                "documentId",
                "product_1"),
            Map.entry(
                "sourceFile",
                "product_1.pdf"),
            Map.entry(
                "revisionPartNumber",
                "10000000123"),
            Map.entry(
                "revision",
                "A"),
            Map.entry(
                "publicationDate",
                "2026-01-01"),
            Map.entry(
                "copyrightYear",
                2026),
            Map.entry(
                "chunkIndex",
                7),
            Map.entry(
                "section",
                "Engine"),
            Map.entry(
                "modelNumbers",
                List.of("G007144-0")),
            Map.entry(
                "pageNumbers",
                List.of(1, 2)),
            Map.entry(
                "sourceElementIds",
                List.of(
                    "engine.type",
                    "engine.note"))))
        .build();
  }
}
