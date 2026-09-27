package com.example.documentassistant.web.demo;

import com.example.documentassistant.ingestion.pipeline.IndexingJobAlreadyRunningException;
import com.example.documentassistant.ingestion.pipeline.IndexingJobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IndexingJobController.class)
public class IndexingJobExceptionHandler {

  @ExceptionHandler(IndexingJobNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(
      IndexingJobNotFoundException exception) {

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.NOT_FOUND,
        exception.getMessage());

    problem.setTitle(
        "Indexing job not found");

    return ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(problem);
  }

  @ExceptionHandler(IndexingJobAlreadyRunningException.class)
  public ResponseEntity<ProblemDetail> handleAlreadyRunning(
      IndexingJobAlreadyRunningException exception) {

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        exception.getMessage());

    problem.setTitle(
        "Indexing job already running");

    problem.setProperty(
        "activeJobId",
        exception.activeJobId().toString());

    return ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(problem);
  }
}
