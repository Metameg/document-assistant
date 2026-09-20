
package com.example.documentassistant.document.pdf;

import com.example.documentassistant.document.model.PdfCharacter;
import com.example.documentassistant.document.model.PdfTextCell;
import com.example.documentassistant.document.model.PdfTextLine;
import com.example.documentassistant.document.model.PdfWord;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class FuelConsumptionLayoutTest {

  @Test
  void inspectFuelConsumptionLayout() throws IOException {

    File pdfFile = new File(
        "data/raw/specsheets/product_1.pdf");

    // Stage 1: Extract positioned characters.
    PdfTextExtractor extractor = new PdfTextExtractor();

    List<PdfCharacter> characters = extractor.extract(pdfFile);

    // Stage 2: Isolate page 3.
    List<PdfCharacter> pageThreeCharacters = characters.stream()
        .filter(character -> character.pageNumber() == 3)
        .toList();

    // Stage 3: Reconstruct lines and cells.
    PdfLayoutAnalyzer analyzer = new PdfLayoutAnalyzer();

    List<PdfTextLine> lines = analyzer.reconstructLines(
        pageThreeCharacters);

    // Stage 4: Inspect the fuel consumption region.
    for (PdfTextLine line : lines) {

      if (line.y() < 365 || line.y() > 475) {
        continue;
      }

      System.out.printf(
          "%nLINE page=%d y=%.2f%n",
          line.pageNumber(),
          line.y());

      for (PdfTextCell cell : line.cells()) {

        System.out.printf(
            "  CELL x=%7.2f w=%7.2f "
                + "endX=%7.2f text=%s%n",
            cell.x(),
            cell.width(),
            cell.x() + cell.width(),
            cell.text());

        for (PdfWord word : cell.words()) {

          System.out.printf(
              "      WORD x=%7.2f "
                  + "y=%7.2f "
                  + "font=%5.2f "
                  + "text=%s%n",
              word.x(),
              word.y(),
              word.fontSize(),
              word.text());
        }
      }
    }
  }
}
