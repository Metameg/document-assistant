
package com.example.documentassistant.document.pdf;

import com.example.documentassistant.document.model.PdfCharacter;
import com.example.documentassistant.document.model.PdfTextLine;
import com.example.documentassistant.document.model.SpecificationRow;
import com.example.documentassistant.document.model.SpecificationSection;
import com.example.documentassistant.document.model.SpecificationTable;
import com.example.documentassistant.document.parser.GeneracSpecificationTableParser;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Page3ExtractionTest {

  @Test
  void printPageThreeSpecifications() throws IOException {

    File pdfFile = new File(
        "data/raw/specsheets/product_1.pdf");

    // Stage 1: Extract positioned characters.
    PdfTextExtractor extractor = new PdfTextExtractor();

    List<PdfCharacter> characters = extractor.extract(pdfFile);

    // Stage 2: Isolate page 3 for this experiment.
    List<PdfCharacter> pageThreeCharacters = characters.stream()
        .filter(character -> character.pageNumber() == 3)
        .toList();

    // Stage 3: Reconstruct words, lines, and cells.
    PdfLayoutAnalyzer analyzer = new PdfLayoutAnalyzer();

    List<PdfTextLine> lines = analyzer.reconstructLines(
        pageThreeCharacters);

    // Stage 4: Interpret the visual table.
    GeneracSpecificationTableParser parser = new GeneracSpecificationTableParser();

    SpecificationTable table = parser.parse(lines);

    // Stage 5: Inspect the semantic representation.
    System.out.println("\nMODELS");

    for (String model : table.models()) {

      System.out.println(
          "  " + model);
    }

    for (SpecificationSection section : table.sections()) {

      System.out.println(
          "\nSECTION: " + section.title());

      for (SpecificationRow row : section.rows()) {

        System.out.println(
            "\n  " + row.name());

        if (row.sharedValue() != null) {

          System.out.println(
              "    shared = "
                  + row.sharedValue());
        }

        for (Map.Entry<String, String> entry : row.valuesByModel().entrySet()) {

          System.out.printf(
              "    %s = %s%n",
              entry.getKey(),
              entry.getValue());
        }
      }
    }
  }
}
