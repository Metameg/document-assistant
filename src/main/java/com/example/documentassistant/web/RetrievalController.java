package com.example.documentassistant.web;

import com.example.documentassistant.retrieval.ChunkRetrievalService;
import com.example.documentassistant.retrieval.RetrievalRequest;
import com.example.documentassistant.retrieval.RetrievalResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

  private final ChunkRetrievalService retrievalService;

  public RetrievalController(
      ChunkRetrievalService retrievalService) {

    this.retrievalService = Objects.requireNonNull(
        retrievalService,
        "retrievalService must not be null");
  }

  @PostMapping("/search")
  public RetrievalResponse search(
      @RequestBody RetrievalRequest request) {

    return retrievalService.search(request);
  }
}
