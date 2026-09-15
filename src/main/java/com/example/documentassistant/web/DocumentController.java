package com.example.documentassistant.web;

import com.example.documentassistant.document.parser.PostgresHtmlParser;
import com.example.documentassistant.document.model.DocumentBlock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DocumentController {

  private static final Set<String> DOCUMENT_NAMES = Set.of(
      "tutorial-createdb",
      "tutorial-table",
      "tutorial-populate",
      "tutorial-select",
      "tutorial-join",
      "tutorial-agg",
      "tutorial-fk",
      "tutorial-transactions");

  private final PostgresHtmlParser parser;

  public DocumentController(PostgresHtmlParser parser) {
    this.parser = parser;
  }

  @GetMapping(value = "/documents/{name}", produces = "text/plain;charset=UTF-8")
  public String preview(@PathVariable("name") String name) {

    if (!DOCUMENT_NAMES.contains(name)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Unknown document");
    }

    Path file = Path.of("data", "raw", name + ".html");

    try {
      List<DocumentBlock> blocks = parser.parse(file);

      return blocks.stream()
          .map(DocumentBlock::text)
          .collect(Collectors.joining("\n\n"));
    } catch (IOException exception) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Document not found");
    }
  }
}
