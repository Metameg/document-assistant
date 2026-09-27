package com.example.documentassistant.rag;

import com.example.documentassistant.retrieval.RetrievalResponse;

import java.util.Objects;

public record AnswerResponse(
    String query,
    String answer,
    RetrievalResponse retrieval) {

  public AnswerResponse {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(answer, "answer must not be null");
    Objects.requireNonNull(retrieval, "retrieval must not be null");
  }
}
