package com.example.documentassistant.document.chunking;

import com.example.documentassistant.document.model.DocumentChunk;
import com.example.documentassistant.document.model.DocumentElement;
import com.example.documentassistant.document.model.ProcessedDocument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * First semantic chunking pass for schema 1.3.
 * Groups by meaning, not character count. Preview sizes before embedding;
 * a large section/table may need a later split at row boundaries.
 * No Spring, embedding provider, database, or PDF parser is required.
 */
public class SpecSheetChunker {

  public List<DocumentChunk> chunk(ProcessedDocument document) {
    // First pass: notes can occur AFTER the specifications they qualify.
    Map<String, DocumentElement> elementsById = new LinkedHashMap<>();
    Map<String, List<NoteBinding>> notesByTarget = new LinkedHashMap<>();
    Set<String> knownModels = new HashSet<>(document.models());

    for (var section : document.sections()) {
      requireModels(section.modelNumbers(), knownModels, section.title());
      for (var element : section.elements()) {
        if (element.elementId() == null || element.elementId().isBlank()
            || elementsById.putIfAbsent(element.elementId(), element) != null) {
          throw new IllegalArgumentException("Missing or duplicate element ID: " + element.elementId());
        }
        if (element.pageNumber() < 1 || element.pageNumber() > document.pageCount()) {
          throw new IllegalArgumentException("Invalid page: " + element.elementId());
        }
        if (element instanceof DocumentElement.TechnicalNote note) {
          var binding = new NoteBinding(note, section.modelNumbers());
          for (String target : note.qualifiesElementIds()) {
            notesByTarget.computeIfAbsent(target, key -> new ArrayList<>()).add(binding);
          }
        }
      }
    }
    for (String target : notesByTarget.keySet()) {
      if (!elementsById.containsKey(target)) {
        throw new IllegalArgumentException("Unresolved note target: " + target);
      }
    }

    List<DocumentChunk> chunks = new ArrayList<>();
    // Second pass: create model-specific specifications, then other content.
    for (var section : document.sections()) {
      List<DocumentElement.Specification> specifications = section.elements().stream()
          .filter(DocumentElement.Specification.class::isInstance)
          .map(DocumentElement.Specification.class::cast).toList();

      for (var specification : specifications) {
        if (!section.modelNumbers().containsAll(specification.valuesByModel().keySet())) {
          throw new IllegalArgumentException("Specification models outside section: " + specification.elementId());
        }
        if (specification.sharedValue() != null && !specification.valuesByModel().isEmpty()) {
          throw new IllegalArgumentException("Both shared and per-model values: " + specification.elementId());
        }
      }
      for (String model : section.modelNumbers()) {
        List<Part> parts = new ArrayList<>();
        for (var specification : specifications) {
          String value = specification.sharedValue() != null
              ? specification.sharedValue() : specification.valuesByModel().get(model);
          if (value != null) {
            String units = specification.units().isEmpty() ? ""
                : " [Units: " + String.join(", ", specification.units()) + "]";
            parts.add(new Part(specification, specification.name() + ": " + value + units));
          }
        }
        emit(document, section, List.of(model), parts, notesByTarget, chunks);
      }

      // Shared narrative stays shared, avoiding a copy for every model.
      List<Part> narrative = new ArrayList<>();
      for (var element : section.elements()) {
        if (element instanceof DocumentElement.Paragraph paragraph) {
          narrative.add(new Part(element, paragraph.text()));
        } else if (element instanceof DocumentElement.ListItem item) {
          narrative.add(new Part(element, item.text()));
        } else if (element instanceof DocumentElement.Feature feature) {
          narrative.add(new Part(element, feature.name() + ": " + feature.description()));
        } else {
          emit(document, section, section.modelNumbers(), narrative, notesByTarget, chunks);
          narrative.clear();
          if (element instanceof DocumentElement.FuelConsumption fuel) {
            if (!section.modelNumbers().containsAll(fuel.measurementsByModel().keySet())) {
              throw new IllegalArgumentException("Fuel models outside section: " + fuel.elementId());
            }
            for (String model : section.modelNumbers()) {
              var measurements = fuel.measurementsByModel().get(model);
              if (measurements != null) {
                String text = "Fuel: " + fuel.fuelType() + "; units: " + fuel.units()
                    + "\nHalf load: " + measurements.halfLoad()
                    + "\nFull load: " + measurements.fullLoad();
                emit(document, section, List.of(model), List.of(new Part(fuel, text)), notesByTarget, chunks);
              }
            }
          } else if (element instanceof DocumentElement.Accessory accessory) {
            requireModels(accessory.applicableGeneratorModels(), knownModels, accessory.elementId());
            if (!section.modelNumbers().containsAll(accessory.applicableGeneratorModels())) {
              throw new IllegalArgumentException("Accessory applicability outside section: " + accessory.elementId());
            }
            StringBuilder text = new StringBuilder("Accessory: ").append(accessory.product());
            Set<String> mappedModels = new HashSet<>();
            if (!accessory.generatorModelsByAccessory().keySet()
                .equals(new HashSet<>(accessory.accessoryModelNumbers()))) {
              throw new IllegalArgumentException("Accessory mapping keys differ: " + accessory.elementId());
            }
            for (String accessoryModel : accessory.accessoryModelNumbers()) {
              List<String> appliesTo = accessory.generatorModelsByAccessory().get(accessoryModel);
              requireModels(appliesTo, new HashSet<>(accessory.applicableGeneratorModels()), accessoryModel);
              mappedModels.addAll(appliesTo);
              text.append("\n").append(accessoryModel).append(" applies to generators: ")
                  .append(String.join(", ", appliesTo));
            }
            if (!mappedModels.equals(new HashSet<>(accessory.applicableGeneratorModels()))) {
              throw new IllegalArgumentException("Accessory applicability differs from mappings: " + accessory.elementId());
            }
            // Keep variant details such as 50/100 amp, color, or network type.
            text.append("\nSource model listing: ").append(accessory.modelText())
                .append("\n").append(accessory.description());
            emit(document, section, accessory.applicableGeneratorModels(),
                List.of(new Part(accessory, text.toString())), notesByTarget, chunks);
          } else if (element instanceof DocumentElement.Table table) {
            emit(document, section, section.modelNumbers(),
                List.of(new Part(table, renderTable(table))), notesByTarget, chunks);
          } else if (element instanceof DocumentElement.DimensionDrawing drawing) {
            emit(document, section, section.modelNumbers(),
                List.of(new Part(drawing, renderDrawing(drawing))), notesByTarget, chunks);
          }
          // Specifications were handled above. Notes are attached by emit().
        }
      }
      emit(document, section, section.modelNumbers(), narrative, notesByTarget, chunks);
    }

    // Retain unlinked notes (e.g. certification), plus any not attached above.
    Set<String> covered = coveredIds(chunks);
    for (var section : document.sections()) {
      for (var element : section.elements()) {
        if (element instanceof DocumentElement.TechnicalNote note && !covered.contains(note.elementId())) {
          emit(document, section, section.modelNumbers(),
              List.of(new Part(note, renderNote(note))), notesByTarget, chunks);
          covered = coveredIds(chunks);
        }
      }
    }
    Set<String> missing = new LinkedHashSet<>(elementsById.keySet());
    missing.removeAll(coveredIds(chunks));
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Elements not represented in chunks: " + missing);
    }
    return List.copyOf(chunks);
  }

  private void emit(ProcessedDocument document, ProcessedDocument.Section section,
      List<String> models, List<Part> parts, Map<String, List<NoteBinding>> notesByTarget,
      List<DocumentChunk> chunks) {
    if (parts.isEmpty()) return;

    StringBuilder text = new StringBuilder("Document: ").append(document.documentId())
        .append("\nModels: ").append(String.join(", ", models))
        .append("\nSection: ").append(section.title());
    Set<String> ids = new LinkedHashSet<>();
    Set<Integer> pages = new TreeSet<>();
    Map<String, NoteBinding> attachedNotes = new LinkedHashMap<>();

    for (Part part : parts) {
      text.append("\n\n[Page ").append(part.element().pageNumber()).append("] ").append(part.text());
      ids.add(part.element().elementId());
      pages.add(part.element().pageNumber());
      for (NoteBinding binding : notesByTarget.getOrDefault(part.element().elementId(), List.of())) {
        if (binding.models().stream().anyMatch(models::contains)) {
          attachedNotes.putIfAbsent(binding.note().elementId(), binding);
        }
      }
    }
    for (NoteBinding binding : attachedNotes.values()) {
      var note = binding.note();
      if (ids.add(note.elementId())) {
        List<String> appliesTo = models.stream().filter(binding.models()::contains).toList();
        text.append("\n\n[Page ").append(note.pageNumber()).append("] Note for models ")
            .append(String.join(", ", appliesTo)).append(": ").append(renderNote(note));
        pages.add(note.pageNumber());
      }
    }
    chunks.add(new DocumentChunk(document.documentId(), document.sourceFile(), chunks.size(),
        section.title(), models, List.copyOf(pages), List.copyOf(ids), text.toString()));
  }

  private String renderNote(DocumentElement.TechnicalNote note) {
    return note.subject() + ": " + note.text()
        + (note.applicability() == null || note.applicability().isBlank()
            ? "" : "\nApplicability: " + note.applicability());
  }

  private String renderTable(DocumentElement.Table table) {
    StringBuilder text = new StringBuilder(table.title());
    if (table.sourceHeading() != null) text.append("\n").append(table.sourceHeading());
    for (var row : table.rows()) {
      text.append("\n");
      List<String> cells = new ArrayList<>();
      for (String column : table.columns()) {
        if (!row.containsKey(column)) throw new IllegalArgumentException("Missing table column: " + column);
        cells.add(column + ": " + row.get(column));
      }
      if (!new HashSet<>(table.columns()).containsAll(row.keySet())) {
        throw new IllegalArgumentException("Unlisted table columns: " + table.elementId());
      }
      text.append(String.join("; ", cells));
    }
    return text.toString();
  }

  private String renderDrawing(DocumentElement.DimensionDrawing drawing) {
    StringBuilder text = new StringBuilder("Generator dimension drawing");
    for (var view : drawing.views()) {
      for (var measurement : view.measurements()) {
        String values = measurement.values().stream()
            .map(value -> value.value() + " " + value.unit()).collect(Collectors.joining(" / "));
        text.append("\n").append(view.name()).append(" - ").append(measurement.name())
            .append(": ").append(values);
      }
    }
    return text.append("\nWarning: ").append(drawing.warning()).toString();
  }

  private Set<String> coveredIds(List<DocumentChunk> chunks) {
    return chunks.stream().flatMap(chunk -> chunk.sourceElementIds().stream()).collect(Collectors.toSet());
  }

  private void requireModels(List<String> models, Set<String> allowed, String location) {
    if (models == null || models.isEmpty() || !allowed.containsAll(models)) {
      throw new IllegalArgumentException("Missing or unknown generator models at: " + location);
    }
  }

  // Small internal helpers: a rendered source element, and a note with its scope.
  private record Part(DocumentElement element, String text) { }
  private record NoteBinding(DocumentElement.TechnicalNote note, List<String> models) { }
}
