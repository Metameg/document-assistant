package com.example.documentassistant.integration;

import com.example.documentassistant.DocumentAssistantApplication;
import com.example.documentassistant.retrieval.ChunkRetrievalService;
import com.example.documentassistant.retrieval.RetrievalRequest;
import com.example.documentassistant.retrieval.RetrievalResponse;
import com.example.documentassistant.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

@SpringBootTest(classes = DocumentAssistantApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.web-application-type=none",
    "spring.ai.model.chat=none",
    "document-assistant.ingestion.chunking.run-on-startup=false",
    "document-assistant.ingestion.vector.run-on-startup=false"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "runPgVectorRetrievalIntegrationTests", matches = "true")
class ChunkRetrievalIntegrationTest {

  private static final int EXPECTED_CORPUS_CHUNKS = 571;

  private final ChunkRetrievalService retrievalService;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  ChunkRetrievalIntegrationTest(
      ChunkRetrievalService retrievalService,
      JdbcTemplate jdbcTemplate) {

    this.retrievalService = retrievalService;
    this.jdbcTemplate = jdbcTemplate;
  }

  private String describeResults(
      RetrievalResponse response) {

    StringBuilder summary = new StringBuilder(
        "Retrieved rankings:\n");

    for (int index = 0; index < response.results().size(); index++) {

      RetrievedChunk result = response.results().get(index);

      summary.append("\nRank: ")
          .append(index + 1)
          .append("\nScore: ")
          .append(result.similarityScore())
          .append("\nDocument: ")
          .append(result.documentId())
          .append("\nSource file: ")
          .append(result.sourceFile())
          .append("\nSection: ")
          .append(result.section())
          .append("\nModels: ")
          .append(result.modelNumbers())
          .append("\nPages: ")
          .append(result.pageNumbers())
          .append("\nText: ")
          .append(result.text())
          .append("\n");
    }

    return summary.toString();
  }

  @Test
  void retrievesEngineEvidenceForKnownGeneratorModel() {
    assertExpectedCorpusIsPresent();

    RetrievalResponse response = retrievalService.search(
        new RetrievalRequest(
            "What type of engine does model G007144-0 use?",
            5));

    assertEquals(
        "What type of engine does model G007144-0 use?",
        response.query());

    assertEquals(5, response.topK());
    assertFalse(response.results().isEmpty());

    String rankingSummary = describeResults(response);

    System.out.println(rankingSummary);

    RetrievedChunk engineResult = response.results().stream()
        .filter(result -> result.documentId().equals("product_1"))
        .filter(result -> result.modelNumbers().contains("G007144-0"))
        .filter(result -> result.section().equals("Engine"))
        .filter(result -> result.text()
            .toUpperCase(Locale.ROOT)
            .contains(
                "GENERAC G-FORCE 500 SERIES"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Expected engine evidence was not present "
                + "in the top five retrieval results.\n"
                + rankingSummary));

    assertTrue(
        engineResult.similarityScore() > 0.0);

    assertEquals(
        "product_1.pdf",
        engineResult.sourceFile());

    assertTrue(
        engineResult.pageNumbers().stream()
            .allMatch(page -> page >= 1));

    assertFalse(
        engineResult.sourceElementIds().isEmpty());

    assertNotNull(
        engineResult.text());

    assertFalse(
        engineResult.text().isBlank());
  }

  private void assertExpectedCorpusIsPresent() {
    Integer rowCount = jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM document_chunk_vectors
            """,
        Integer.class);

    assertNotNull(rowCount);

    assertEquals(
        EXPECTED_CORPUS_CHUNKS,
        rowCount,
        "The retrieval integration test requires "
            + "the verified 571-chunk corpus");
  }
}
