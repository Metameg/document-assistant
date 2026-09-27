package com.example.documentassistant.document.corpus;

import com.example.documentassistant.document.catalog.CatalogDocument;
import com.example.documentassistant.document.catalog.DocumentCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class CorpusStatusService {

  private static final String STATUS_QUERY = """
      SELECT
        metadata ->> 'documentId' AS document_id,
        COUNT(*) AS chunk_count
      FROM document_chunk_vectors
      GROUP BY metadata ->> 'documentId'
      ORDER BY document_id
      """;

  private final DocumentCatalogService catalogService;
  private final JdbcTemplate jdbcTemplate;

  public CorpusStatusService(
      DocumentCatalogService catalogService,
      JdbcTemplate jdbcTemplate) {

    this.catalogService = catalogService;
    this.jdbcTemplate = jdbcTemplate;
  }

  public CorpusStatus getStatus() {
    Set<String> catalogDocumentIds = new TreeSet<>();

    for (CatalogDocument document : catalogService.listDocuments()) {
      catalogDocumentIds.add(
          document.documentId());
    }

    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        STATUS_QUERY);

    Set<String> indexedDocumentIds = new TreeSet<>();

    long storedChunkCount = 0;

    for (Map<String, Object> row : rows) {
      Object documentIdValue = row.get("document_id");

      Object chunkCountValue = row.get("chunk_count");

      if (!(chunkCountValue instanceof Number number)) {
        throw new IllegalStateException(
            "Corpus status query returned "
                + "an invalid chunk count");
      }

      long chunkCount = number.longValue();

      storedChunkCount += chunkCount;

      if (documentIdValue instanceof String documentId
          && !documentId.isBlank()
          && chunkCount > 0) {
        indexedDocumentIds.add(
            documentId);
      }
    }

    Set<String> missingDocumentIds = new TreeSet<>(
        catalogDocumentIds);

    missingDocumentIds.removeAll(
        indexedDocumentIds);

    Set<String> unexpectedDocumentIds = new TreeSet<>(
        indexedDocumentIds);

    unexpectedDocumentIds.removeAll(
        catalogDocumentIds);

    boolean indexed = storedChunkCount > 0;

    boolean inSync = !catalogDocumentIds.isEmpty()
        && missingDocumentIds.isEmpty()
        && unexpectedDocumentIds.isEmpty();

    return new CorpusStatus(
        indexed,
        inSync,
        catalogDocumentIds.size(),
        indexedDocumentIds.size(),
        Math.toIntExact(storedChunkCount),
        List.copyOf(missingDocumentIds),
        List.copyOf(unexpectedDocumentIds),
        Instant.now());
  }
}
