package com.example.documentassistant.document.chunking;

import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.DocumentElement;
import com.example.documentassistant.document.model.ProcessedDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SpecSheetChunkerTest {
  private final SpecSheetChunker chunker = new SpecSheetChunker();
  private final JsonMapper mapper = JsonMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE).build();

  @Test
  void previewsBothDocumentsAndChecksCoverageAndKeyRelationships() throws IOException {
    for (String id : List.of("product_1", "product_18")) {
      Path input = Path.of("data", "processed", "specsheets", id + ".json");
      ProcessedDocument document = mapper.readValue(Files.readString(input), ProcessedDocument.class);
      List<DocumentChunk> chunks = chunker.chunk(document);
      assertFalse(chunks.isEmpty());
      assertEquals(chunks, chunker.chunk(document), "Chunk order and content should be deterministic");
      verifyCoverageAndCitations(document, chunks);

      if (id.equals("product_1")) {
        var engine = find(chunks, "G007144-0", "Engine", "Type of Engine:");
        assertEquals(List.of("G007144-0"), engine.modelNumbers());
        assertTrue(engine.text().contains("GENERAC G-FORCE 500 SERIES"));
        assertTrue(engine.text().contains("530 cc"));
        assertTrue(engine.text().contains("Governor System: Electronic"));
        assertFalse(engine.text().contains("999 cc"));

        var generator = find(chunks, "G007144-0", "Generator", "Main Line Circuit Breaker:");
        assertTrue(generator.text().contains("Main Line Circuit Breaker: 40 Amp"));
        assertFalse(generator.text().contains("50 Amp"));
        assertTrue(generator.text().contains("Maximum power decreases"));

        var fuel = find(chunks, "G007144-0", "Fuel Consumption", "Fuel: Natural Gas");
        assertTrue(fuel.text().contains("units: m³/hr (ft³/hr)"));
        assertTrue(fuel.text().contains("Half load: 2.21 (78)"));
        assertTrue(fuel.text().contains("Full load: 3.62 (128)"));
        assertTrue(fuel.text().contains("Fuel pipe must be sized for full load"));
        assertFalse(fuel.text().contains("5.30 (187)"));

        var accessory = find(chunks, "G007144-0", "Available Accessories", "Scheduled Maintenance Kit");
        assertTrue(accessory.text().contains("G006483-0 applies to generators: G007144-0"));
        assertTrue(accessory.text().contains("G006485-0 applies to generators: G007145-0, G007146-0"));
      } else {
        var generator = find(chunks, "G007290-0", "Generator", "26,000 Watts*");
        assertTrue(generator.text().contains("22,500 Watts*"));
        var fuel = find(chunks, "G007291-0", "Fuel Consumption", "Fuel: Liquid Propane");
        assertTrue(fuel.text().contains("Full load: 132 (3.63) [13.73]"));

        var transfer = find(chunks, "G007291-0", "Transfer Switch Specifications", "Current rating (amps): 200");
        assertEquals(List.of("G007291-0"), transfer.modelNumbers());
        assertTrue(transfer.text().contains("Function of Evolution controller"));
        assertTrue(chunks.stream().filter(c -> c.section().startsWith("Transfer Switch"))
            .allMatch(c -> c.modelNumbers().equals(List.of("G007291-0"))));
        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("CETL or CUL certification")));
      }
      assertTrue(chunks.stream().anyMatch(c ->
          c.section().equals("Generator Dimension Drawings")
              && c.text().contains("DO NOT USE THESE DIMENSIONS FOR INSTALLATION PURPOSES")));
      writePreview(document, chunks);
    }
  }

  @Test
  void attachesALaterPageNoteOnceAndIncludesBothCitationPages() {
    var first = new DocumentElement.Specification("power", "Power", List.of("W"), "100 W", Map.of(), 1);
    var second = new DocumentElement.Specification("current", "Current", List.of("A"), "2 A", Map.of(), 1);
    var note = new DocumentElement.TechnicalNote("limit", "Test limitation", "Rating conditions",
        List.of("power", "current"), null, 2);
    var document = sample(List.of(first, second), note);
    List<DocumentChunk> chunks = chunker.chunk(document);

    assertEquals(1, chunks.size());
    assertEquals(List.of(1, 2), chunks.get(0).pageNumbers());
    assertEquals(List.of("power", "current", "limit"), chunks.get(0).sourceElementIds());
    assertEquals(1, chunks.get(0).text().split("Test limitation", -1).length - 1);
  }

  @Test
  void rejectsDanglingNoteReferences() {
    var specification = new DocumentElement.Specification("power", "Power", List.of("W"), "100 W", Map.of(), 1);
    var note = new DocumentElement.TechnicalNote("limit", "Test limitation", "Conditions",
        List.of("missing-element"), null, 2);
    var exception = assertThrows(IllegalArgumentException.class,
        () -> chunker.chunk(sample(List.of(specification), note)));
    assertTrue(exception.getMessage().contains("Unresolved note target"));
  }

  private ProcessedDocument sample(List<DocumentElement> elements, DocumentElement.TechnicalNote note) {
    return new ProcessedDocument("1.3", "sample", "sample.pdf", "generator_spec_sheet", 2,
        List.of("MODEL-A"), null,
        List.of(new ProcessedDocument.Section("Generator", 1, List.of("MODEL-A"), List.of(), elements),
            new ProcessedDocument.Section("Technical Notes", 2, List.of("MODEL-A"), List.of(), List.of(note))), null);
  }

  private DocumentChunk find(List<DocumentChunk> chunks, String model, String section, String text) {
    return chunks.stream().filter(c -> c.modelNumbers().contains(model)
        && c.section().equals(section) && c.text().contains(text)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing chunk: " + model + " / " + section + " / " + text));
  }

  private void verifyCoverageAndCitations(ProcessedDocument document, List<DocumentChunk> chunks) {
    Map<String, DocumentElement> originals = document.sections().stream()
        .flatMap(s -> s.elements().stream()).collect(Collectors.toMap(DocumentElement::elementId, e -> e));
    Set<String> covered = new HashSet<>();
    for (int i = 0; i < chunks.size(); i++) {
      var chunk = chunks.get(i);
      assertEquals(i, chunk.chunkIndex());
      assertEquals(document.documentId(), chunk.documentId());
      assertEquals(document.sourceFile(), chunk.sourceFile());
      assertFalse(chunk.text().isBlank());
      assertFalse(chunk.modelNumbers().isEmpty());
      assertTrue(document.models().containsAll(chunk.modelNumbers()));
      Set<Integer> expectedPages = new TreeSet<>();
      for (String sourceId : chunk.sourceElementIds()) {
        assertTrue(originals.containsKey(sourceId), "Invented source ID: " + sourceId);
        expectedPages.add(originals.get(sourceId).pageNumber());
        covered.add(sourceId);
      }
      assertEquals(List.copyOf(expectedPages), chunk.pageNumbers());
    }
    assertEquals(originals.keySet(), covered, "Every JSON element should be represented");
  }

  private void writePreview(ProcessedDocument document, List<DocumentChunk> chunks) throws IOException {
    StringBuilder preview = new StringBuilder();
    for (var chunk : chunks) {
      preview.append("CHUNK ").append(chunk.chunkIndex()).append(" | characters: ").append(chunk.text().length())
          .append("\nSource: ").append(chunk.sourceFile()).append(" | pages: ").append(chunk.pageNumbers())
          .append("\nElement IDs: ").append(chunk.sourceElementIds()).append("\n\n")
          .append(chunk.text()).append("\n\n----------------------------------------\n\n");
    }
    Path destination = Path.of("target", "chunk-previews", document.documentId() + "-chunks.txt");
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, preview.toString());
    System.out.print(preview);
    int longest = chunks.stream().mapToInt(c -> c.text().length()).max().orElse(0);
    System.out.printf("%s: %d chunks, longest %d characters. Full preview: %s%n",
        document.documentId(), chunks.size(), longest, destination.toAbsolutePath());
  }
}
