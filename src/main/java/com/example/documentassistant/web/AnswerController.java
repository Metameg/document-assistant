package com.example.documentassistant.web;

import com.example.documentassistant.rag.HybridAnswerService;
import com.example.documentassistant.rag.HybridAnswerService.HybridAnswerResponse;
import com.example.documentassistant.retrieval.RetrievalRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class AnswerController {

  private final HybridAnswerService answerService;

  public AnswerController(
      HybridAnswerService answerService) {

    this.answerService = answerService;
  }

  @PostMapping("/answer")
  public HybridAnswerResponse answer(
      @RequestBody RetrievalRequest request) {

    return answerService.answer(request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      IllegalArgumentException exception) {

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        exception.getMessage());

    problem.setTitle("Invalid answer request");

    return ResponseEntity
        .badRequest()
        .body(problem);
  }
}
