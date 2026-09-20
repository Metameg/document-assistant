package com.example.documentassistant.document.pdf;

import com.example.documentassistant.document.model.PdfCharacter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfTextExtractor {

  public List<PdfCharacter> extract(File pdfFile) throws IOException {
    List<PdfCharacter> characters = new ArrayList<>();

    try (PDDocument document = Loader.loadPDF(pdfFile)) {

      PositionAwareTextStripper stripper = new PositionAwareTextStripper(characters);

      stripper.setSortByPosition(true);

      stripper.getText(document);
    }

    return characters;
  }

  private static class PositionAwareTextStripper
      extends PDFTextStripper {

    private final List<PdfCharacter> characters;

    PositionAwareTextStripper(
        List<PdfCharacter> characters) throws IOException {
      this.characters = characters;
    }

    @Override
    protected void processTextPosition(
        TextPosition text) {

      String unicode = text.getUnicode();

      if (unicode == null || unicode.isBlank()) {
        return;
      }

      characters.add(new PdfCharacter(
          getCurrentPageNo(),
          text.getXDirAdj(),
          text.getYDirAdj(),
          text.getWidthDirAdj(),
          text.getHeightDir(),
          text.getFontSizeInPt(),
          text.getWidthOfSpace(),
          unicode));
    }
  }
}
