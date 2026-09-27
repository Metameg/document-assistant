package com.example.documentassistant.web.demo;

import com.example.documentassistant.document.catalog.DocumentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DocumentCatalogController.class)
public class DocumentCatalogExceptionHandler {

  @ExceptionHandler(DocumentNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleDocumentNotFound(
      DocumentNotFoundException exception) {

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.NOT_FOUND,
        exception.getMessage());

    problem.setTitle(
        "Document not found");

    return ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(problem);
  }
}
