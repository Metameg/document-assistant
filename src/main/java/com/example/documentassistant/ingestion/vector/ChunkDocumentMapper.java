package com.example.documentassistant.ingestion.vector;

import com.example.documentassistant.document.model.ChunkedDocument;
import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.ProcessedDocument;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts the application's semantic chunks into Spring AI Documents.
 *
 * <p>
 * DocumentChunk remains the application's domain model. Spring AI Document
 * is the representation passed to a VectorStore.
 * </p>
 */
public class ChunkDocumentMapper {

  public List<Document> toDocuments(ChunkedDocument chunkedDocument) {
    Objects.requireNonNull(
        chunkedDocument,
        "chunkedDocument must not be null");

    validateDocument(chunkedDocument);

    List<Document> documents = new ArrayList<>(chunkedDocument.chunks().size());

    for (DocumentChunk chunk : chunkedDocument.chunks()) {
      documents.add(toDocument(chunkedDocument, chunk));
    }

    return List.copyOf(documents);
  }

  private Document toDocument(
      ChunkedDocument parent,
      DocumentChunk chunk) {

    validateChunk(parent, chunk);

    Map<String, Object> metadata = new LinkedHashMap<>();

    // Document-level metadata
    metadata.put("documentId", parent.documentId());
    metadata.put("sourceFile", parent.sourceFile());
    metadata.put("chunkSchemaVersion", parent.chunkSchemaVersion());
    metadata.put("sourceSchemaVersion", parent.sourceSchemaVersion());

    // Chunk-level citation and filtering metadata
    metadata.put("chunkIndex", chunk.chunkIndex());
    metadata.put("section", chunk.section());
    metadata.put("modelNumbers", chunk.modelNumbers());
    metadata.put("pageNumbers", chunk.pageNumbers());
    metadata.put("sourceElementIds", chunk.sourceElementIds());

    addRevisionMetadata(metadata, parent.revision());

    return Document.builder()
        .id(createChunkId(parent, chunk))
        .text(chunk.text())
        .metadata(metadata)
        .build();
  }

  /**
   * Creates a repeatable UUID from document identity, revision, and chunk index.
   *
   * <p>
   * The same chunk receives the same ID on every ingestion run. PostgreSQL's
   * pgvector table uses UUID identifiers, so returning a UUID string also keeps
   * the ID compatible with that schema.
   * </p>
   */
  private String createChunkId(
      ChunkedDocument parent,
      DocumentChunk chunk) {

    String revisionIdentity = revisionIdentity(parent.revision());

    String identity = String.join(
        "\u001F",
        parent.documentId(),
        revisionIdentity,
        Integer.toString(chunk.chunkIndex()));

    return UUID.nameUUIDFromBytes(
        identity.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String revisionIdentity(
      ProcessedDocument.Revision revision) {

    if (revision == null) {
      return "unrevisioned";
    }

    return String.join(
        "\u001F",
        valueOrEmpty(revision.partNumber()),
        valueOrEmpty(revision.revision()),
        valueOrEmpty(revision.publicationDate()),
        Integer.toString(revision.copyrightYear()));
  }

  private void addRevisionMetadata(
      Map<String, Object> metadata,
      ProcessedDocument.Revision revision) {

    // Spring AI Document metadata cannot contain null values.
    if (revision == null) {
      return;
    }

    putIfPresent(
        metadata,
        "revisionPartNumber",
        revision.partNumber());

    putIfPresent(
        metadata,
        "revision",
        revision.revision());

    putIfPresent(
        metadata,
        "publicationDate",
        revision.publicationDate());

    metadata.put(
        "copyrightYear",
        revision.copyrightYear());
  }

  private void putIfPresent(
      Map<String, Object> metadata,
      String key,
      String value) {

    if (value != null && !value.isBlank()) {
      metadata.put(key, value);
    }
  }

  private void validateDocument(
      ChunkedDocument document) {

    if (document.documentId() == null
        || document.documentId().isBlank()) {
      throw new IllegalArgumentException(
          "Chunked document must have a documentId");
    }

    if (document.sourceFile() == null
        || document.sourceFile().isBlank()) {
      throw new IllegalArgumentException(
          "Chunked document must have a sourceFile");
    }

    if (document.chunks() == null
        || document.chunks().isEmpty()) {
      throw new IllegalArgumentException(
          "Chunked document must contain chunks");
    }

    if (document.chunkCount() != document.chunks().size()) {
      throw new IllegalArgumentException(
          "Declared chunkCount does not match chunks list for "
              + document.documentId());
    }
  }

  private void validateChunk(
      ChunkedDocument parent,
      DocumentChunk chunk) {

    if (chunk == null) {
      throw new IllegalArgumentException(
          "Chunk list must not contain null values");
    }

    if (!parent.documentId().equals(chunk.documentId())) {
      throw new IllegalArgumentException(
          "Chunk documentId does not match parent: "
              + chunk.chunkIndex());
    }

    if (!parent.sourceFile().equals(chunk.sourceFile())) {
      throw new IllegalArgumentException(
          "Chunk sourceFile does not match parent: "
              + chunk.chunkIndex());
    }

    if (chunk.chunkIndex() < 0) {
      throw new IllegalArgumentException(
          "Chunk index must not be negative");
    }

    if (chunk.text() == null || chunk.text().isBlank()) {
      throw new IllegalArgumentException(
          "Chunk text must not be blank: "
              + chunk.documentId()
              + ":"
              + chunk.chunkIndex());
    }
  }

  private String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }
}
