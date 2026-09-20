package com.example.documentassistant.ingestion.chunking;

import com.example.documentassistant.document.model.ChunkedDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Tests batch orchestration without starting Spring, PostgreSQL, or an LLM. */
class DocumentChunkingServiceTest {
  @TempDir
  Path temporary;

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final DocumentChunkingService service = new DocumentChunkingService(mapper);

  @Test
  void discoversDocumentsExportsChunksAndStartsANewFolderOnRerun() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("input"));
    Path output = temporary.resolve("output");
    Files.writeString(input.resolve("first.json"), document("first"));
    Files.writeString(input.resolve("second.json"), document("second"));
    Files.writeString(input.resolve("README.txt"), "Not a processed document");

    var result = service.chunkDirectory(input, output);
    assertTrue(result.complete());
    assertEquals(2, result.discoveredFiles());
    assertEquals(2, result.successfulDocuments());
    assertEquals(0, result.failedDocuments());
    assertEquals(2, result.totalChunks());
    assertEquals(Set.of("first", "second"), result.documents().stream()
        .map(DocumentChunkingService.DocumentResult::documentId).collect(Collectors.toSet()));

    for (var success : result.documents()) {
      var saved = mapper.readValue(Files.readString(Path.of(success.outputFile())),
          ChunkedDocument.class);
      assertEquals(success.documentId(), saved.documentId());
      assertEquals("1.3", saved.sourceSchemaVersion());
      assertEquals(1, saved.chunkCount());
      var chunk = saved.chunks().get(0);
      assertEquals(saved.documentId() + ".pdf", chunk.sourceFile());
      assertEquals(List.of("MODEL-A"), chunk.modelNumbers());
      assertEquals(List.of(1), chunk.pageNumbers());
      assertEquals(List.of("engine.governor"), chunk.sourceElementIds());
      assertTrue(chunk.text().contains("Electronic governor: Maintains frequency."));
    }
    var savedReport = mapper.readValue(
        Files.readString(Path.of(result.outputDirectory(), "batch-result.json")),
        DocumentChunkingService.BatchResult.class);
    assertEquals(result, savedReport);

    var repeated = service.chunkDirectory(input, output);
    assertNotEquals(result.outputDirectory(), repeated.outputDirectory());
    assertTrue(Files.isRegularFile(Path.of(result.documents().get(0).outputFile())));
  }

  @Test
  void recordsBadInputButStillProcessesTheOtherDocuments() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("input"));
    Files.writeString(input.resolve("00-bad.json"),
        document("bad").replace("\"schemaVersion\"", "\"unknownField\": true, \"schemaVersion\""));
    Files.writeString(input.resolve("valid.json"), document("valid"));

    var result = service.chunkDirectory(input, temporary.resolve("output"));
    assertFalse(result.complete());
    assertEquals(1, result.successfulDocuments());
    assertEquals(1, result.failedDocuments());
    assertEquals("valid", result.documents().get(0).documentId());
    assertTrue(result.failures().get(0).reason().contains("unknownField"));
    assertTrue(Files.isRegularFile(Path.of(result.outputDirectory(), "batch-result.json")));

    var runner = new ChunkingRunner(service, input.toString(), temporary.resolve("runner-output").toString());
    assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments(new String[0])));
  }

  @Test
  void rejectsDuplicateDocumentIdsWithoutOverwritingTheFirstExport() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("input"));
    Files.writeString(input.resolve("a.json"), document("same"));
    Files.writeString(input.resolve("b.json"), document("same"));
    var result = service.chunkDirectory(input, temporary.resolve("output"));
    assertFalse(result.complete());
    assertEquals(1, result.successfulDocuments());
    assertEquals(1, result.failedDocuments());
    assertTrue(result.failures().get(0).reason().contains("Duplicate documentId"));
    var saved = mapper.readValue(Files.readString(Path.of(result.documents().get(0).outputFile())),
        ChunkedDocument.class);
    assertEquals("same", saved.documentId());
    assertEquals(1, saved.chunks().size());
  }

  @Test
  void reportsAnEmptyInputDirectoryInsteadOfClaimingSuccess() throws Exception {
    Path input = Files.createDirectory(temporary.resolve("empty"));
    var exception = assertThrows(IllegalArgumentException.class,
        () -> service.chunkDirectory(input, temporary.resolve("output")));
    assertTrue(exception.getMessage().contains("No processed JSON files"));
  }

  private String document(String id) {
    return """
        {
          "schemaVersion": "1.3",
          "documentId": "%s",
          "sourceFile": "%s.pdf",
          "documentType": "generator_spec_sheet",
          "pageCount": 1,
          "models": ["MODEL-A"],
          "revision": null,
          "sections": [{
            "title": "Engine",
            "pageNumber": 1,
            "modelNumbers": ["MODEL-A"],
            "sourceModelNumbers": [],
            "elements": [{
              "type": "feature",
              "elementId": "engine.governor",
              "name": "Electronic governor",
              "description": "Maintains frequency.",
              "pageNumber": 1
            }]
          }],
          "extraction": null
        }
        """.formatted(id, id);
  }
}
