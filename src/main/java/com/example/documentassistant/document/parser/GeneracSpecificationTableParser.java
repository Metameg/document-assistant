
package com.example.documentassistant.document.parser;

import com.example.documentassistant.document.model.PdfTextCell;
import com.example.documentassistant.document.model.PdfTextLine;
import com.example.documentassistant.document.model.SpecificationRow;
import com.example.documentassistant.document.model.SpecificationSection;
import com.example.documentassistant.document.model.SpecificationTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeneracSpecificationTableParser {

  private static final Pattern MODEL_NUMBER_PATTERN = Pattern.compile("\\bG\\d{6,}-\\d+\\b");

  private static final float SECTION_FONT_DIFFERENCE = 0.5f;

  private static final float MAX_CONTINUATION_GAP = 15.0f;

  public SpecificationTable parse(List<PdfTextLine> lines) {

    int headerIndex = findModelHeaderIndex(lines);

    if (headerIndex == -1) {
      throw new IllegalArgumentException(
          "Could not find specification table model header");
    }

    PdfTextLine headerLine = lines.get(headerIndex);

    List<ModelColumn> modelColumns = extractModelColumns(headerLine);

    if (modelColumns.isEmpty()) {
      throw new IllegalArgumentException(
          "Model header did not contain any model numbers");
    }

    float bodyFontSize = maxFontSize(headerLine);

    String currentSectionTitle = findSectionBeforeHeader(
        lines,
        headerIndex,
        bodyFontSize);

    List<SpecificationSection> sections = new ArrayList<>();

    List<SpecificationRow> currentRows = new ArrayList<>();

    PdfTextLine previousRowLine = null;

    for (int i = headerIndex + 1; i < lines.size(); i++) {

      PdfTextLine line = lines.get(i);

      if (isPageFurniture(line)) {
        continue;
      }

      // Special handling for the nested fuel-consumption table.
      if (isFuelConsumptionHeading(line)) {

        FuelParseResult result = parseFuelConsumption(
            lines,
            i,
            modelColumns);

        currentRows.addAll(result.rows());

        // Skip the visual lines consumed by the fuel parser.
        i = result.lastConsumedIndex();

        previousRowLine = null;

        continue;
      }

      // Section headings such as Generator, Engine, Controls.
      if (isSectionHeading(line, bodyFontSize)) {

        if (!currentRows.isEmpty()) {

          sections.add(
              new SpecificationSection(
                  currentSectionTitle,
                  List.copyOf(currentRows),
                  List.of()));

          currentRows = new ArrayList<>();
        }

        currentSectionTitle = line.cells().getFirst().text();

        previousRowLine = null;

        continue;
      }

      // Handle wrapped model-specific values.
      if (!currentRows.isEmpty()
          && previousRowLine != null
          && isValueContinuation(
              line,
              previousRowLine,
              currentRows.getLast(),
              modelColumns)) {

        int lastIndex = currentRows.size() - 1;

        SpecificationRow previousRow = currentRows.get(lastIndex);

        SpecificationRow updatedRow = appendContinuation(
            previousRow,
            line,
            modelColumns);

        currentRows.set(lastIndex, updatedRow);

        previousRowLine = line;

        continue;
      }

      SpecificationRow row = parseSpecificationRow(
          line,
          modelColumns);

      if (row != null) {

        currentRows.add(row);

        previousRowLine = line;

      } else {

        printUnrecognized(line);

        previousRowLine = null;
      }
    }

    if (!currentRows.isEmpty()) {

      sections.add(
          new SpecificationSection(
              currentSectionTitle,
              List.copyOf(currentRows),
              List.of()));
    }

    List<String> models = modelColumns.stream()
        .map(ModelColumn::modelNumber)
        .toList();

    return new SpecificationTable(
        models,
        sections);
  }

  // ============================================================
  // Model header detection
  // ============================================================

  private int findModelHeaderIndex(
      List<PdfTextLine> lines) {

    for (int i = 0; i < lines.size(); i++) {

      PdfTextLine line = lines.get(i);

      if (line.cells().isEmpty()) {
        continue;
      }

      String firstCell = line.cells().getFirst().text();

      if (firstCell.equalsIgnoreCase("Model")) {
        return i;
      }
    }

    return -1;
  }

  private List<ModelColumn> extractModelColumns(
      PdfTextLine headerLine) {

    List<ModelColumn> columns = new ArrayList<>();

    for (PdfTextCell cell : headerLine.cells()) {

      Matcher matcher = MODEL_NUMBER_PATTERN.matcher(
          cell.text());

      if (!matcher.find()) {
        continue;
      }

      String modelNumber = matcher.group();

      float centerX = cell.x() + cell.width() / 2.0f;

      columns.add(
          new ModelColumn(
              modelNumber,
              cell.x(),
              centerX));
    }

    return columns;
  }

  // ============================================================
  // Ordinary specification rows
  // ============================================================

  private SpecificationRow parseSpecificationRow(
      PdfTextLine line,
      List<ModelColumn> modelColumns) {

    if (line.cells().size() < 2) {
      return null;
    }

    float firstModelStartX = modelColumns.getFirst().startX();

    PdfTextCell firstCell = line.cells().getFirst();

    if (firstCell.x() >= firstModelStartX) {
      return null;
    }

    String name = firstCell.text();

    List<PdfTextCell> valueCells = line.cells().subList(
        1,
        line.cells().size());

    // Current multi-model shared-value heuristic.
    if (valueCells.size() == 1) {

      return new SpecificationRow(
          name,
          valueCells.getFirst().text(),
          Map.of());
    }

    Map<String, String> valuesByModel = mapValuesToModels(
        valueCells,
        modelColumns);

    return new SpecificationRow(
        name,
        null,
        valuesByModel);
  }

  // ============================================================
  // Fuel-consumption parsing
  // ============================================================

  private boolean isFuelConsumptionHeading(
      PdfTextLine line) {

    return line.cells().size() == 1
        && line.cells()
            .getFirst()
            .text()
            .equalsIgnoreCase("Fuel Consumption");
  }

  private FuelParseResult parseFuelConsumption(
      List<PdfTextLine> lines,
      int startIndex,
      List<ModelColumn> modelColumns) {

    List<SpecificationRow> rows = new ArrayList<>();

    List<PdfTextLine> fuelLines = new ArrayList<>();

    int lastConsumedIndex = startIndex;

    /*
     * Collect everything after "Fuel Consumption"
     * until the explanatory note or the next major section.
     */
    for (int i = startIndex + 1; i < lines.size(); i++) {

      PdfTextLine line = lines.get(i);

      if (line.cells().isEmpty()) {
        continue;
      }

      String firstCell = line.cells().getFirst().text();

      if (firstCell.startsWith("Note:")
          || firstCell.equalsIgnoreCase("Controls")) {
        break;
      }

      fuelLines.add(line);

      lastConsumedIndex = i;
    }

    int naturalGasIndex = findLineIndex(
        fuelLines,
        "Natural Gas");

    int liquidPropaneIndex = findLineIndex(
        fuelLines,
        "Liquid Propane");

    if (naturalGasIndex == -1
        || liquidPropaneIndex == -1
        || naturalGasIndex >= liquidPropaneIndex) {

      throw new IllegalStateException(
          "Could not reconstruct fuel-consumption groups");
    }

    /*
     * Natural Gas:
     * From the Natural Gas heading up to,
     * but not including, Liquid Propane.
     */
    List<PdfTextLine> naturalGasLines = fuelLines.subList(
        naturalGasIndex,
        liquidPropaneIndex);

    /*
     * Liquid Propane:
     * From its heading to the end of the
     * fuel-consumption region.
     */
    List<PdfTextLine> liquidPropaneLines = fuelLines.subList(
        liquidPropaneIndex,
        fuelLines.size());

    rows.addAll(
        parseFuelGroup(
            "Natural Gas",
            naturalGasLines,
            modelColumns));

    rows.addAll(
        parseFuelGroup(
            "Liquid Propane",
            liquidPropaneLines,
            modelColumns));

    return new FuelParseResult(
        rows,
        lastConsumedIndex);
  }

  private List<SpecificationRow> parseFuelGroup(
      String fuelType,
      List<PdfTextLine> lines,
      List<ModelColumn> modelColumns) {

    List<String> loadLabels = new ArrayList<>();

    List<PdfTextLine> valueLines = new ArrayList<>();

    String units = null;

    float firstModelStartX = modelColumns.getFirst().startX();

    for (PdfTextLine line : lines) {

      if (line.cells().isEmpty()) {
        continue;
      }

      PdfTextCell firstCell = line.cells().getFirst();

      String text = firstCell.text();

      // Extract units from the fuel-type heading.
      if (text.equalsIgnoreCase(fuelType)) {

        if (line.cells().size() > 1) {

          units = line.cells()
              .get(1)
              .text();
        }

        continue;
      }

      // Recognize load-condition labels.
      if (text.equalsIgnoreCase("1/2 Load")
          || text.equalsIgnoreCase("Full Load")) {

        loadLabels.add(text);

        /*
         * Sometimes the label and values are on
         * the same reconstructed visual line.
         *
         * Example:
         * Full Load | 3.62 (128) | 5.30 (187) | 6.48 (229)
         */
        if (line.cells().size() > 1) {

          List<PdfTextCell> valueCells = line.cells().subList(
              1,
              line.cells().size());

          boolean validValueCells = valueCells.size() == modelColumns.size()
              && valueCells.stream()
                  .allMatch(cell -> cell.x() >= firstModelStartX);

          if (!validValueCells) {
            throw new IllegalStateException(
                "Unexpected values accompanying "
                    + fuelType
                    + " "
                    + text);
          }

          /*
           * Create a new line containing only the value cells.
           * This allows the existing value-row logic to work
           * without changing our semantic representation.
           */
          valueLines.add(
              new PdfTextLine(
                  line.pageNumber(),
                  line.y(),
                  valueCells.stream()
                      .flatMap(cell -> cell.words().stream())
                      .toList(),
                  valueCells));
        }

        continue;
      }

      /*
       * A value row contains one cell per model,
       * with all cells in the model-value area.
       */
      boolean isValueLine = line.cells().size() == modelColumns.size()
          && line.cells().stream()
              .allMatch(cell -> cell.x() >= firstModelStartX);

      if (isValueLine) {

        valueLines.add(line);

        continue;
      }

      /*
       * Ignore a standalone superscript only when
       * it is part of the known LPG unit formatting.
       *
       * It will be restored as m³/h below.
       */
      if (text.equals("3")
          && line.cells().size() == 1
          && line.words().stream()
              .allMatch(word -> word.fontSize() < 7.0f)) {

        continue;
      }

      throw new IllegalStateException(
          "Unexpected fuel-consumption line: "
              + line.pageNumber()
              + " y="
              + line.y()
              + " "
              + line.cells().stream()
                  .map(PdfTextCell::text)
                  .toList());
    }

    if (units == null) {

      throw new IllegalStateException(
          "Missing units for " + fuelType);
    }

    /*
     * In this PDF, the superscript 3 in [m³/h LPG]
     * is positioned on a separate visual line.
     */
    if (fuelType.equals("Liquid Propane")) {

      units = units.replace(
          "[m /h LPG]",
          "[m³/h LPG]");
    }

    /*
     * The first version expects half-load and full-load
     * measurements for each fuel type.
     *
     * Fail instead of silently losing measurements.
     */
    if (loadLabels.size() != 2
        || valueLines.size() != 2) {

      throw new IllegalStateException(
          "Expected two load labels and two value rows "
              + "for "
              + fuelType
              + ", found "
              + loadLabels.size()
              + " labels and "
              + valueLines.size()
              + " value rows");
    }

    List<SpecificationRow> rows = new ArrayList<>();

    /*
     * The visual Y coordinates are not identical,
     * but their ordering within the group is preserved.
     */
    for (int i = 0; i < loadLabels.size(); i++) {

      String loadCondition = loadLabels.get(i);

      PdfTextLine valueLine = valueLines.get(i);

      Map<String, String> values = mapValuesToModels(
          valueLine.cells(),
          modelColumns);

      String specificationName = fuelType
          + " - "
          + loadCondition
          + " ("
          + units
          + ")";

      rows.add(
          new SpecificationRow(
              specificationName,
              null,
              values));
    }

    return rows;
  }

  private int findLineIndex(
      List<PdfTextLine> lines,
      String text) {

    for (int i = 0; i < lines.size(); i++) {

      PdfTextLine line = lines.get(i);

      if (!line.cells().isEmpty()
          && line.cells()
              .getFirst()
              .text()
              .equalsIgnoreCase(text)) {

        return i;
      }
    }

    return -1;
  }

  // ============================================================
  // Model column mapping
  // ============================================================

  private Map<String, String> mapValuesToModels(
      List<PdfTextCell> valueCells,
      List<ModelColumn> modelColumns) {

    Map<String, String> values = new LinkedHashMap<>();

    for (PdfTextCell cell : valueCells) {

      ModelColumn column = findClosestModelColumn(
          cell,
          modelColumns);

      values.put(
          column.modelNumber(),
          cell.text());
    }

    if (values.size() != valueCells.size()) {

      throw new IllegalStateException(
          "Multiple value cells mapped to the same model");
    }

    return values;
  }

  private ModelColumn findClosestModelColumn(
      PdfTextCell cell,
      List<ModelColumn> modelColumns) {

    float cellCenterX = cell.x() + cell.width() / 2.0f;

    return modelColumns.stream()
        .min(
            Comparator.comparingDouble(
                column -> Math.abs(
                    cellCenterX
                        - column.centerX())))
        .orElseThrow();
  }

  // ============================================================
  // Wrapped-value handling
  // ============================================================

  private boolean isValueContinuation(
      PdfTextLine line,
      PdfTextLine previousLine,
      SpecificationRow previousRow,
      List<ModelColumn> modelColumns) {

    if (line.cells().isEmpty()) {
      return false;
    }

    if (previousRow.valuesByModel().isEmpty()) {
      return false;
    }

    if (line.pageNumber() != previousLine.pageNumber()) {
      return false;
    }

    float verticalGap = line.y() - previousLine.y();

    if (verticalGap <= 0
        || verticalGap > MAX_CONTINUATION_GAP) {
      return false;
    }

    float firstModelStartX = modelColumns.getFirst().startX();

    boolean allCellsInValueArea = line.cells().stream()
        .allMatch(cell -> cell.x() >= firstModelStartX);

    if (!allCellsInValueArea) {
      return false;
    }

    if (line.cells().size() > modelColumns.size()) {
      return false;
    }

    for (PdfTextCell cell : line.cells()) {

      ModelColumn column = findClosestModelColumn(
          cell,
          modelColumns);

      if (!previousRow.valuesByModel()
          .containsKey(column.modelNumber())) {
        return false;
      }
    }

    return true;
  }

  private SpecificationRow appendContinuation(
      SpecificationRow previousRow,
      PdfTextLine continuationLine,
      List<ModelColumn> modelColumns) {

    Map<String, String> updatedValues = new LinkedHashMap<>(
        previousRow.valuesByModel());

    for (PdfTextCell cell : continuationLine.cells()) {

      ModelColumn column = findClosestModelColumn(
          cell,
          modelColumns);

      String modelNumber = column.modelNumber();

      String existingValue = updatedValues.get(modelNumber);

      if (existingValue != null) {

        updatedValues.put(
            modelNumber,
            existingValue + " " + cell.text());
      }
    }

    return new SpecificationRow(
        previousRow.name(),
        previousRow.sharedValue(),
        updatedValues);
  }

  // ============================================================
  // Section and page-furniture detection
  // ============================================================

  private String findSectionBeforeHeader(
      List<PdfTextLine> lines,
      int headerIndex,
      float bodyFontSize) {

    for (int i = headerIndex - 1; i >= 0; i--) {

      PdfTextLine line = lines.get(i);

      if (isPageFurniture(line)) {
        continue;
      }

      if (isSectionHeading(
          line,
          bodyFontSize)) {

        return line.cells()
            .getFirst()
            .text();
      }
    }

    return "Unknown";
  }

  private boolean isSectionHeading(
      PdfTextLine line,
      float bodyFontSize) {

    if (line.cells().size() != 1) {
      return false;
    }

    return maxFontSize(line) >= bodyFontSize
        + SECTION_FONT_DIFFERENCE;
  }

  private boolean isPageFurniture(
      PdfTextLine line) {

    if (line.cells().size() != 1) {
      return false;
    }

    String text = line.cells()
        .getFirst()
        .text()
        .strip();

    return text.equals("®")
        || text.matches("\\d+\\s+of\\s+\\d+");
  }

  private float maxFontSize(
      PdfTextLine line) {

    return line.words()
        .stream()
        .map(word -> word.fontSize())
        .max(Float::compare)
        .orElse(0.0f);
  }

  private void printUnrecognized(
      PdfTextLine line) {

    System.out.printf(
        "UNRECOGNIZED page=%d y=%.2f: %s%n",
        line.pageNumber(),
        line.y(),
        line.cells()
            .stream()
            .map(PdfTextCell::text)
            .toList());
  }

  // ============================================================
  // Internal parser records
  // ============================================================

  private record ModelColumn(
      String modelNumber,
      float startX,
      float centerX) {
  }

  private record FuelParseResult(
      List<SpecificationRow> rows,
      int lastConsumedIndex) {
  }
}
