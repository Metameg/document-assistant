package com.example.documentassistant.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RetrievalController.class)
public class RetrievalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleInvalidRequest(
      IllegalArgumentException exception) {

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        exception.getMessage());

    problem.setTitle("Invalid retrieval request");

    return ResponseEntity
        .badRequest()
        .body(problem);
  }
}
