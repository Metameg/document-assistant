
package com.example.documentassistant.document;

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

import static org.junit.jupiter.api.Assertions.*;

/** Reads the real schema 1.2 files without starting Spring or a database. */
class ProcessedDocumentJsonTest {

  private final JsonMapper mapper = JsonMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
      .build();

  @Test
  void readsMultiModelSpecificationsAndFuelConsumption() throws IOException {
    ProcessedDocument document = readDocument("product_1");

    assertEquals("product_1.pdf", document.sourceFile());
    assertEquals(4, document.pageCount());
    assertEquals(List.of("G007144-0", "G007145-0", "G007146-0"), document.models());

    var breaker = findElement(document,
        "p3.generator.specification.main-line-circuit-breaker",
        DocumentElement.Specification.class);
    assertEquals(Map.of("G007144-0", "40 Amp", "G007145-0", "50 Amp",
        "G007146-0", "63 Amp"), breaker.valuesByModel());
    assertNull(breaker.sharedValue());

    var gas = findElement(document,
        "p3.fuel-consumption.fuel-consumption.natural-gas",
        DocumentElement.FuelConsumption.class);
    assertEquals("m³/hr (ft³/hr)", gas.units());
    assertEquals(new DocumentElement.LoadMeasurements("2.21 (78)", "3.62 (128)"),
        gas.measurementsByModel().get("G007144-0"));
    assertEquals(3, gas.pageNumber());

    var note = findElement(document,
        "p3.technical-notes.technical-note.fuel-consumption",
        DocumentElement.TechnicalNote.class);
    assertTrue(note.qualifiesElementIds().contains(gas.elementId()));
    assertTrue(note.text().contains("Fuel pipe must be sized for full load"));

    System.out.println("Breaker values: " + breaker.valuesByModel());
    System.out.println("Natural gas: " + gas);
  }

  @Test
  void readsSharedRatingsAndModelSpecificTransferSwitch() throws IOException {
    ProcessedDocument document = readDocument("product_18");

    assertEquals("product_18.pdf", document.sourceFile());
    assertEquals(6, document.pageCount());
    assertEquals(List.of("G007290-0", "G007291-0"), document.models());

    var power = findElement(document,
        "p4.generator.specification.rated-maximum-continuous-power-capacity-lp",
        DocumentElement.Specification.class);
    assertEquals("26,000 Watts*", power.sharedValue());
    assertTrue(power.valuesByModel().isEmpty());

    var switchSection = document.sections().stream()
        .filter(section -> section.title().equals("Transfer Switch Specifications"))
        .findFirst().orElseThrow();
    assertEquals(List.of("G007291-0"), switchSection.modelNumbers());

    var current = findElement(document,
        "p5.transfer-switch-specifications.specification.current-rating-amps",
        DocumentElement.Specification.class);
    assertEquals(Map.of("G007291-0", "200"), current.valuesByModel());

    var drawing = findElement(document,
        "p6.generator-dimension-drawings.dimension-drawing.generator-dimensions",
        DocumentElement.DimensionDrawing.class);
    assertEquals(2, drawing.views().size());
    assertTrue(drawing.warning().contains("DO NOT USE THESE DIMENSIONS FOR INSTALLATION"));

    System.out.println("LP power: " + power.sharedValue());
    System.out.println("Transfer switch models: " + switchSection.modelNumbers());
  }

  private ProcessedDocument readDocument(String documentId) throws IOException {
    Path path = Path.of("data", "processed", "specsheets", documentId + ".json");
    assertTrue(Files.isRegularFile(path), "Missing JSON file: " + path.toAbsolutePath());

    String json = Files.readString(path);
    ProcessedDocument document = mapper.readValue(json, ProcessedDocument.class);

    assertEquals("1.3", document.schemaVersion());
    assertEquals(documentId, document.documentId());
    assertFalse(document.sections().isEmpty());
    verifySourceReferences(document);
    System.out.printf("Read %s: %d pages, %d models, %d sections%n",
        document.documentId(), document.pageCount(), document.models().size(),
        document.sections().size());
    return document;
  }

  private void verifySourceReferences(ProcessedDocument document) {
    List<DocumentElement> elements = document.sections().stream()
        .flatMap(section -> section.elements().stream()).toList();
    Set<String> ids = new HashSet<>();

    for (DocumentElement element : elements) {
      assertNotNull(element.elementId());
      assertFalse(element.elementId().isBlank());
      assertTrue(ids.add(element.elementId()), "Duplicate ID: " + element.elementId());
      assertTrue(element.pageNumber() >= 1 && element.pageNumber() <= document.pageCount(),
          "Invalid page for: " + element.elementId());
    }
    for (DocumentElement element : elements) {
      if (element instanceof DocumentElement.TechnicalNote note) {
        for (String targetId : note.qualifiesElementIds()) {
          assertTrue(ids.contains(targetId), "Unresolved note reference: " + targetId);
        }
      }
    }
  }

  private <T extends DocumentElement> T findElement(
      ProcessedDocument document, String id, Class<T> expectedType) {
    DocumentElement element = document.sections().stream()
        .flatMap(section -> section.elements().stream())
        .filter(candidate -> candidate.elementId().equals(id))
        .findFirst().orElseThrow(() -> new AssertionError("Missing element: " + id));
    return assertInstanceOf(expectedType, element);
  }
}
