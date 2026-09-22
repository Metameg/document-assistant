package com.example.documentassistant.integration;

import com.example.documentassistant.DocumentAssistantApplication;
import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import com.example.documentassistant.ingestion.vector.ChunkVectorIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = DocumentAssistantApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.web-application-type=none",
    "spring.ai.model.chat=none"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "runPgVectorIntegrationTests", matches = "true")
class PgVectorIngestionIntegrationTest {

  private static final String TEST_DOCUMENT_ID = "pgvector_integration_test";

  private final ChunkVectorIngestionService ingestionService;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  PgVectorIngestionIntegrationTest(
      ChunkVectorIngestionService ingestionService,
      JdbcTemplate jdbcTemplate) {

    this.ingestionService = ingestionService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  void embedsAndStoresChunkInLocalPgVector() {
    deleteTestRows();

    try {
      var result = ingestionService.ingest(testDocument());

      assertEquals(TEST_DOCUMENT_ID, result.documentId());
      assertEquals(1, result.storedChunks());

      assertVectorExtensionInstalled();
      assertStoredRow();
    } finally {
      deleteTestRows();
    }
  }

  private void assertVectorExtensionInstalled() {
    Boolean installed = jdbcTemplate.queryForObject(
        """
            SELECT EXISTS (
              SELECT 1
              FROM pg_extension
              WHERE extname = 'vector'
            )
            """,
        Boolean.class);

    assertTrue(Boolean.TRUE.equals(installed));
  }

  private void assertStoredRow() {
    Integer rowCount = jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        Integer.class,
        TEST_DOCUMENT_ID);

    assertEquals(1, rowCount);

    Integer dimensions = jdbcTemplate.queryForObject(
        """
            SELECT vector_dims(embedding)
            FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        Integer.class,
        TEST_DOCUMENT_ID);

    assertEquals(1536, dimensions);

    String storedText = jdbcTemplate.queryForObject(
        """
            SELECT content
            FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        String.class,
        TEST_DOCUMENT_ID);

    assertTrue(
        storedText.contains("GENERAC G-FORCE 500 SERIES"));

    String sourceFile = jdbcTemplate.queryForObject(
        """
            SELECT metadata ->> 'sourceFile'
            FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        String.class,
        TEST_DOCUMENT_ID);

    assertEquals(
        "pgvector-integration-test.pdf",
        sourceFile);

    Integer chunkIndex = jdbcTemplate.queryForObject(
        """
            SELECT (metadata ->> 'chunkIndex')::integer
            FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        Integer.class,
        TEST_DOCUMENT_ID);

    assertEquals(0, chunkIndex);
  }

  private void deleteTestRows() {
    jdbcTemplate.update(
        """
            DELETE FROM document_chunk_vectors
            WHERE metadata ->> 'documentId' = ?
            """,
        TEST_DOCUMENT_ID);
  }

  private ChunkedDocument testDocument() {
    var revision = new ProcessedDocument.Revision(
        "integration-test-part",
        "A",
        "2026-09-21",
        2026);

    var chunk = new DocumentChunk(
        TEST_DOCUMENT_ID,
        "pgvector-integration-test.pdf",
        0,
        "Engine",
        List.of("TEST-MODEL"),
        List.of(1),
        List.of("test.engine.type"),
        """
            Document: pgvector_integration_test
            Models: TEST-MODEL
            Section: Engine

            [Page 1] Type of Engine: GENERAC G-FORCE 500 SERIES
            """);

    return new ChunkedDocument(
        "1",
        "1.3",
        TEST_DOCUMENT_ID,
        "pgvector-integration-test.pdf",
        revision,
        1,
        chunk.text().length(),
        List.of(chunk));
  }
}
