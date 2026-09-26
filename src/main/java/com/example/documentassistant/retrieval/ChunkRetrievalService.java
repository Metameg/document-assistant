package com.example.documentassistant.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs semantic retrieval and converts Spring AI documents
 * into citation-rich application results.
 */
@Service
public class ChunkRetrievalService {

  private final VectorStore vectorStore;
  private final RetrievalProperties properties;
  private static final Pattern MODEL_NUMBER_PATTERN = Pattern.compile(
      "\\bG\\d{6}(?:-\\d+)?\\b",
      Pattern.CASE_INSENSITIVE);

  public ChunkRetrievalService(
      VectorStore vectorStore,
      RetrievalProperties properties) {

    this.vectorStore = Objects.requireNonNull(
        vectorStore,
        "vectorStore must not be null");

    this.properties = Objects.requireNonNull(
        properties,
        "properties must not be null");
  }

  public RetrievalResponse search(RetrievalRequest request) {
    Objects.requireNonNull(
        request,
        "request must not be null");

    int topK = request.topK() == null
        ? properties.defaultTopK()
        : request.topK();

    if (topK > properties.maxTopK()) {
      throw new IllegalArgumentException(
          "topK must not exceed configured maximum of "
              + properties.maxTopK());
    }

    var searchRequestBuilder = SearchRequest.builder()
        .query(request.query())
        .topK(topK)
        .similarityThreshold(
            properties.similarityThreshold());

    extractModelNumber(request.query())
        .ifPresent(modelNumber -> searchRequestBuilder.filterExpression(
            "modelNumbers == '" + modelNumber + "'"));

    SearchRequest searchRequest = searchRequestBuilder.build();

    List<Document> matches = vectorStore.similaritySearch(searchRequest);

    if (matches == null) {
      matches = List.of();
    }

    List<RetrievedChunk> results = matches.stream()
        .map(this::toRetrievedChunk)
        .toList();

    return new RetrievalResponse(
        request.query(),
        topK,
        properties.similarityThreshold(),
        results.size(),
        results);
  }

  private Optional<String> extractModelNumber(
      String query) {

    Matcher matcher = MODEL_NUMBER_PATTERN.matcher(query);

    if (!matcher.find()) {
      return Optional.empty();
    }

    return Optional.of(
        matcher.group().toUpperCase(Locale.ROOT));
  }

  private RetrievedChunk toRetrievedChunk(
      Document document) {

    if (document == null) {
      throw new IllegalStateException(
          "Vector store returned a null document");
    }

    if (document.getScore() == null) {
      throw new IllegalStateException(
          "Vector store result is missing a similarity score");
    }

    if (document.getText() == null
        || document.getText().isBlank()) {

      throw new IllegalStateException(
          "Vector store result is missing chunk text");
    }

    Map<String, Object> metadata = document.getMetadata();

    return new RetrievedChunk(
        document.getScore(),
        requiredString(metadata, "documentId"),
        requiredString(metadata, "sourceFile"),
        optionalString(metadata, "revisionPartNumber"),
        optionalString(metadata, "revision"),
        optionalString(metadata, "publicationDate"),
        optionalInteger(metadata, "copyrightYear"),
        requiredInteger(metadata, "chunkIndex"),
        requiredString(metadata, "section"),
        stringList(metadata, "modelNumbers"),
        integerList(metadata, "pageNumbers"),
        stringList(metadata, "sourceElementIds"),
        document.getText());
  }

  private String requiredString(
      Map<String, Object> metadata,
      String key) {

    String value = optionalString(metadata, key);

    if (value == null || value.isBlank()) {
      throw invalidMetadata(key);
    }

    return value;
  }

  private String optionalString(
      Map<String, Object> metadata,
      String key) {

    Object value = metadata.get(key);

    if (value == null) {
      return null;
    }

    if (value instanceof String string) {
      return string;
    }

    throw invalidMetadata(key);
  }

  private int requiredInteger(
      Map<String, Object> metadata,
      String key) {

    Integer value = optionalInteger(metadata, key);

    if (value == null) {
      throw invalidMetadata(key);
    }

    return value;
  }

  private Integer optionalInteger(
      Map<String, Object> metadata,
      String key) {

    Object value = metadata.get(key);

    if (value == null) {
      return null;
    }

    if (value instanceof Number number) {
      return number.intValue();
    }

    throw invalidMetadata(key);
  }

  private List<String> stringList(
      Map<String, Object> metadata,
      String key) {

    Object value = metadata.get(key);

    if (!(value instanceof List<?> values)) {
      throw invalidMetadata(key);
    }

    List<String> result = new ArrayList<>(values.size());

    for (Object item : values) {
      if (!(item instanceof String string)) {
        throw invalidMetadata(key);
      }

      result.add(string);
    }

    return List.copyOf(result);
  }

  private List<Integer> integerList(
      Map<String, Object> metadata,
      String key) {

    Object value = metadata.get(key);

    if (!(value instanceof List<?> values)) {
      throw invalidMetadata(key);
    }

    List<Integer> result = new ArrayList<>(values.size());

    for (Object item : values) {
      if (!(item instanceof Number number)) {
        throw invalidMetadata(key);
      }

      result.add(number.intValue());
    }

    return List.copyOf(result);
  }

  private IllegalStateException invalidMetadata(
      String key) {

    return new IllegalStateException(
        "Vector store result has invalid metadata: "
            + key);
  }
}
