package com.example.documentassistant.document.corpus;

import com.example.documentassistant.document.catalog.CatalogDocument;
import com.example.documentassistant.document.catalog.DocumentCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorpusStatusServiceTest {

  @Test
  void reportsReadyCorpusWhenCatalogMatchesDatabase() {
    DocumentCatalogService catalogService = mock(DocumentCatalogService.class);

    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    when(catalogService.listDocuments())
        .thenReturn(List.of(
            catalogDocument("product_1"),
            catalogDocument("product_18")));

    when(jdbcTemplate.queryForList(
        anyString()))
        .thenReturn(List.of(
            Map.of(
                "document_id",
                "product_1",
                "chunk_count",
                320L),
            Map.of(
                "document_id",
                "product_18",
                "chunk_count",
                251L)));

    CorpusStatusService service = new CorpusStatusService(
        catalogService,
        jdbcTemplate);

    CorpusStatus status = service.getStatus();

    assertTrue(status.indexed());
    assertTrue(status.inSync());

    assertEquals(
        2,
        status.catalogDocumentCount());

    assertEquals(
        2,
        status.indexedDocumentCount());

    assertEquals(
        571,
        status.storedChunkCount());

    assertEquals(
        List.of(),
        status.missingDocumentIds());

    assertEquals(
        List.of(),
        status.unexpectedDocumentIds());

    assertNotNull(status.checkedAt());
  }

  @Test
  void reportsMissingAndUnexpectedDocuments() {
    DocumentCatalogService catalogService = mock(DocumentCatalogService.class);

    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    when(catalogService.listDocuments())
        .thenReturn(List.of(
            catalogDocument("product_1"),
            catalogDocument("product_18")));

    when(jdbcTemplate.queryForList(
        anyString()))
        .thenReturn(List.of(
            Map.of(
                "document_id",
                "product_1",
                "chunk_count",
                100L),
            Map.of(
                "document_id",
                "stale_product",
                "chunk_count",
                20L)));

    CorpusStatusService service = new CorpusStatusService(
        catalogService,
        jdbcTemplate);

    CorpusStatus status = service.getStatus();

    assertTrue(status.indexed());
    assertFalse(status.inSync());

    assertEquals(
        List.of("product_18"),
        status.missingDocumentIds());

    assertEquals(
        List.of("stale_product"),
        status.unexpectedDocumentIds());

    assertEquals(
        120,
        status.storedChunkCount());
  }

  @Test
  void reportsEmptyDatabaseAsNotIndexed() {
    DocumentCatalogService catalogService = mock(DocumentCatalogService.class);

    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    when(catalogService.listDocuments())
        .thenReturn(List.of(
            catalogDocument("product_1")));

    when(jdbcTemplate.queryForList(
        anyString()))
        .thenReturn(List.of());

    CorpusStatusService service = new CorpusStatusService(
        catalogService,
        jdbcTemplate);

    CorpusStatus status = service.getStatus();

    assertFalse(status.indexed());
    assertFalse(status.inSync());

    assertEquals(
        1,
        status.catalogDocumentCount());

    assertEquals(
        0,
        status.indexedDocumentCount());

    assertEquals(
        0,
        status.storedChunkCount());

    assertEquals(
        List.of("product_1"),
        status.missingDocumentIds());

    assertEquals(
        List.of(),
        status.unexpectedDocumentIds());
  }

  private CatalogDocument catalogDocument(
      String documentId) {

    return new CatalogDocument(
        documentId,
        documentId + ".pdf",
        4,
        List.of("TEST-MODEL"),
        null,
        true);
  }
}
