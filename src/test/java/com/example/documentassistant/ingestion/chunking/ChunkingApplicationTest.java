package com.example.documentassistant.ingestion.chunking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the offline entry point itself, including lifecycle and isolation. */
class ChunkingApplicationTest {
  @TempDir
  Path temporary;

  @Test
  void batchConfigurationIsInactiveWithoutTheIngestionProfile() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(ChunkingApplication.BatchConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(ChunkingRunner.class).isEmpty());
      assertTrue(context.getBeansOfType(DocumentChunkingService.class).isEmpty());
    }
  }

  @Test
  void runsWithoutWebDatabaseOrAiBeansAndReturnsZero() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("input"));
    Path output = temporary.resolve("output");
    Files.writeString(input.resolve("sample.json"), sample());
    String[] arguments = {
        "--app.chunking.input-directory=" + input,
        "--app.chunking.output-directory=" + output
    };

    try (var context = ChunkingApplication.start(arguments)) {
      assertInstanceOf(AnnotationConfigApplicationContext.class, context);
      assertEquals(1, context.getBeansOfType(ChunkingRunner.class).size());
      assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
      for (String name : context.getBeanDefinitionNames()) {
        Class<?> type = context.getType(name);
        if (type != null) {
          assertFalse(type.getName().startsWith("org.springframework.ai."), type.getName());
        }
      }
    }
    assertEquals(0, ChunkingApplication.execute(arguments));
    try (var runs = Files.list(output)) {
      assertEquals(2L, runs.count());
    }
  }

  @Test
  void returnsNonzeroAndSavesAReportForBadJson() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("input"));
    Path output = temporary.resolve("output");
    Files.writeString(input.resolve("bad.json"), "{bad json}");
    assertEquals(1, ChunkingApplication.execute(
        "--app.chunking.input-directory=" + input,
        "--app.chunking.output-directory=" + output));
    try (var runs = Files.list(output)) {
      Path run = runs.findFirst().orElseThrow();
      assertTrue(Files.isRegularFile(run.resolve("batch-result.json")));
    }
  }

  private String sample() {
    return """
        {
          "schemaVersion": "1.3", "documentId": "sample",
          "sourceFile": "sample.pdf", "documentType": "generator_spec_sheet",
          "pageCount": 1, "models": ["MODEL-A"], "revision": null,
          "sections": [{
            "title": "Engine", "pageNumber": 1,
            "modelNumbers": ["MODEL-A"], "sourceModelNumbers": [],
            "elements": [{
              "type": "feature", "elementId": "engine.governor",
              "name": "Electronic governor", "description": "Maintains frequency.",
              "pageNumber": 1
            }]
          }], "extraction": null
        }
        """;
  }
}
