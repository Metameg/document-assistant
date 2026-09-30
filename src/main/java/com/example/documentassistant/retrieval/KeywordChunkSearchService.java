package com.example.documentassistant.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

@Service
public class KeywordChunkSearchService {

  /*
   * Keep this expression equivalent to the expression in
   * scripts/create_keyword_search_index.sql.
   *
   * The table alias differs, but the indexed values and weights
   * must match.
   */
  private static final String SEARCH_TEXT = """
      (
        setweight(
          to_tsvector(
            'english',
            coalesce(v.metadata->>'section', '')
          ),
          'A'
        )
        ||
        setweight(
          to_tsvector(
            'english',
            coalesce(v.content, '')
          ),
          'D'
        )
      )
      """;

  private final JdbcTemplate jdbcTemplate;
  private final String qualifiedTable;

  public KeywordChunkSearchService(
      JdbcTemplate jdbcTemplate,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {

    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);

    // SQL identifiers cannot be JDBC parameters.
    if (!schemaName.matches("[A-Za-z_][A-Za-z0-9_]*")
        || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException(
          "Invalid pgvector schema or table name");
    }

    this.qualifiedTable = schemaName + "." + tableName;
  }

  /**
   * Returns the globally highest-ranked matching chunks.
   */
  public List<KeywordCandidate> search(String query, int topK) {
    validateQuery(query);
    validateLimit(topK, "topK");

    String sql = scoredMatchesSql() + """
        SELECT
          keyword_score,
          document_id,
          source_file,
          section,
          chunk_index,
          model_numbers,
          content
        FROM scored
        ORDER BY
          keyword_score DESC,
          document_id,
          chunk_index
        LIMIT ?
        """;

    return jdbcTemplate.query(
        sql,
        statement -> {
          statement.setString(1, query.trim());
          statement.setInt(2, topK);
        },
        this::mapCandidate);
  }

  /**
   * Compatibility method used by the retrieval trace.
   */
  public List<KeywordCandidate> searchAcrossDocuments(
      String query,
      int documentLimit) {

    return searchAcrossDocuments(query, 1, documentLimit);
  }

  /**
   * Selects matching documents by their best keyword score,
   * then returns up to perDocument chunks from each.
   *
   * This is useful for investigating top-K crowding. It is not
   * proof that every relevant chunk was selected.
   */
  public List<KeywordCandidate> searchAcrossDocuments(
      String query,
      int perDocument,
      int documentLimit) {

    validateQuery(query);
    validateLimit(perDocument, "perDocument");
    validateLimit(documentLimit, "documentLimit");

    String sql = scoredMatchesSql() + """
        , ranked AS (
          SELECT
            s.*,
            row_number() OVER (
              PARTITION BY s.document_id
              ORDER BY s.keyword_score DESC, s.chunk_index
            ) AS document_rank,
            max(s.keyword_score) OVER (
              PARTITION BY s.document_id
            ) AS document_score
          FROM scored s
        ),
        selected_documents AS (
          SELECT document_id, document_score
          FROM ranked
          GROUP BY document_id, document_score
          ORDER BY document_score DESC, document_id
          LIMIT ?
        )
        SELECT
          r.keyword_score,
          r.document_id,
          r.source_file,
          r.section,
          r.chunk_index,
          r.model_numbers,
          r.content
        FROM ranked r
        JOIN selected_documents d
          ON d.document_id = r.document_id
        WHERE r.document_rank <= ?
        ORDER BY
          d.document_score DESC,
          r.document_id,
          r.document_rank
        """;

    return jdbcTemplate.query(
        sql,
        statement -> {
          statement.setString(1, query.trim());
          statement.setInt(2, documentLimit);
          statement.setInt(3, perDocument);
        },
        this::mapCandidate);
  }

  private String scoredMatchesSql() {
    return """
        WITH terms AS (
          SELECT replace(
            plainto_tsquery('english', ?)::text,
            ' & ',
            ' | '
          )::tsquery AS search_terms
        ),
        scored AS (
          SELECT
            ts_rank_cd(
              %s,
              t.search_terms
            ) AS keyword_score,
            v.metadata->>'documentId' AS document_id,
            v.metadata->>'sourceFile' AS source_file,
            v.metadata->>'section' AS section,
            (v.metadata->>'chunkIndex')::integer AS chunk_index,
            v.metadata->>'modelNumbers' AS model_numbers,
            v.content
          FROM %s v
          CROSS JOIN terms t
          WHERE %s @@ t.search_terms
        )
        """.formatted(
        SEARCH_TEXT,
        qualifiedTable,
        SEARCH_TEXT);
  }

  private KeywordCandidate mapCandidate(
      ResultSet result,
      int rowNumber) throws SQLException {

    return new KeywordCandidate(
        result.getDouble("keyword_score"),
        result.getString("document_id"),
        result.getString("source_file"),
        result.getString("section"),
        result.getInt("chunk_index"),
        result.getString("model_numbers"),
        result.getString("content"));
  }

  private void validateQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException(
          "query must not be blank");
    }
  }

  private void validateLimit(int value, String name) {
    if (value < 1 || value > 100) {
      throw new IllegalArgumentException(
          name + " must be between 1 and 100");
    }
  }

  public record KeywordCandidate(
      double keywordScore,
      String documentId,
      String sourceFile,
      String section,
      int chunkIndex,
      String modelNumbers,
      String text) {
  }
}
