
package com.example.documentassistant.document.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Jackson reads the JSON "type" property to choose a concrete record.
 * The discriminator is handled by Jackson, so records do not repeat it.
 * Only the explicitly listed JSON type names are registered here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DocumentElement.Paragraph.class, name = "paragraph"),
    @JsonSubTypes.Type(value = DocumentElement.ListItem.class, name = "list_item"),
    @JsonSubTypes.Type(value = DocumentElement.Feature.class, name = "feature"),
    @JsonSubTypes.Type(value = DocumentElement.Specification.class, name = "specification"),
    @JsonSubTypes.Type(value = DocumentElement.FuelConsumption.class, name = "fuel_consumption"),
    @JsonSubTypes.Type(value = DocumentElement.TechnicalNote.class, name = "technical_note"),
    @JsonSubTypes.Type(value = DocumentElement.Accessory.class, name = "accessory"),
    @JsonSubTypes.Type(value = DocumentElement.Table.class, name = "table"),
    @JsonSubTypes.Type(value = DocumentElement.DimensionDrawing.class, name = "dimension_drawing")
})
public sealed interface DocumentElement
    permits DocumentElement.Paragraph, DocumentElement.ListItem,
    DocumentElement.Feature, DocumentElement.Specification,
    DocumentElement.FuelConsumption, DocumentElement.TechnicalNote,
    DocumentElement.Accessory, DocumentElement.Table,
    DocumentElement.DimensionDrawing {

  String elementId();

  int pageNumber();

  record Paragraph(String elementId, String text, int pageNumber)
      implements DocumentElement {
  }

  record ListItem(String elementId, String text, int pageNumber)
      implements DocumentElement {
  }

  record Feature(String elementId, String name, String description, int pageNumber)
      implements DocumentElement {
  }

  // Preserve source strings, including unit labels, footnotes, and paired values.
  // A sharedValue applies to the generator models on the containing section.
  record Specification(
      String elementId,
      String name,
      List<String> units,
      String sharedValue,
      Map<String, String> valuesByModel,
      int pageNumber) implements DocumentElement {
  }

  // Fuel units are a string in schema 1.2, unlike specification unit arrays.
  record FuelConsumption(
      String elementId,
      String fuelType,
      String units,
      Map<String, LoadMeasurements> measurementsByModel,
      int pageNumber) implements DocumentElement {
  }

  record LoadMeasurements(String halfLoad, String fullLoad) {
  }

  // References are scoped to the containing document, not the whole corpus.
  // An empty reference list does not mean the note should be discarded.
  record TechnicalNote(
      String elementId,
      String text,
      String subject,
      List<String> qualifiesElementIds,
      String applicability,
      int pageNumber) implements DocumentElement {
  }

  record Accessory(
      String elementId,
      List<String> accessoryModelNumbers,
      List<String> applicableGeneratorModels,
      Map<String, List<String>> generatorModelsByAccessory,
      String modelText,
      String product,
      String description,
      int pageNumber) implements DocumentElement {
  }

  // Render rows in columns order rather than relying on map iteration order.
  record Table(
      String elementId,
      String title,
      String sourceHeading,
      List<String> columns,
      List<Map<String, String>> rows,
      int pageNumber) implements DocumentElement {
  }

  record DimensionDrawing(
      String elementId,
      List<DrawingView> views,
      String warning,
      int pageNumber) implements DocumentElement {
  }

  record DrawingView(String name, List<DrawingMeasurement> measurements) {
  }

  record DrawingMeasurement(String name, List<DimensionValue> values) {
  }

  record DimensionValue(String value, String unit) {
  }
}
