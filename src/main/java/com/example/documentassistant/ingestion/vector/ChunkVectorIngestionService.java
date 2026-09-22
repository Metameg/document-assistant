package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Objects;

/**
 * Stores one chunked source document in the configured vector store.
 *
 * <p>
 * The VectorStore calls the configured EmbeddingModel internally and then
 * saves the text, metadata, and resulting vectors in PostgreSQL.
 * </p>
 */
public class ChunkVectorIngestionService {

  private final ChunkDocumentMapper documentMapper;
  private final VectorStore vectorStore;

  public ChunkVectorIngestionService(
      ChunkDocumentMapper documentMapper,
      VectorStore vectorStore) {

    this.documentMapper = Objects.requireNonNull(
        documentMapper,
        "documentMapper must not be null");

    this.vectorStore = Objects.requireNonNull(
        vectorStore,
        "vectorStore must not be null");
  }

  /**
   * Replaces every stored chunk for one source document.
   *
   * <p>
   * Deleting by documentId removes chunks from older revisions as well as
   * chunks that disappeared when the source document was reprocessed. Adding
   * the current set then creates fresh embeddings.
   * </p>
   */
  public IngestionResult ingest(ChunkedDocument source) {
    Objects.requireNonNull(
        source,
        "source must not be null");

    List<Document> documents = documentMapper.toDocuments(source);

    var filters = new FilterExpressionBuilder();

    vectorStore.delete(
        filters.eq("documentId", source.documentId()).build());

    vectorStore.add(documents);

    return new IngestionResult(
        source.documentId(),
        documents.size());
  }

  public record IngestionResult(
      String documentId,
      int storedChunks) {
  }
}
