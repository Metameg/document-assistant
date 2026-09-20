package com.example.documentassistant.document.pdf;

import com.example.documentassistant.document.model.PdfCharacter;
import com.example.documentassistant.document.model.PdfTextLine;
import com.example.documentassistant.document.model.PdfWord;
import com.example.documentassistant.document.model.PdfTextCell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class PdfLayoutAnalyzer {

  private static final float LINE_Y_TOLERANCE = 1.5f;
  private static final float SPACE_THRESHOLD_FACTOR = 0.5f;
  private static final float CELL_GAP_THRESHOLD = 12.0f;

  public List<PdfTextLine> reconstructLines(
      List<PdfCharacter> characters) {
    List<PdfCharacter> sortedCharacters = characters.stream()
        .sorted(
            Comparator
                .comparingInt(PdfCharacter::pageNumber)
                .thenComparing(PdfCharacter::y)
                .thenComparing(PdfCharacter::x))
        .toList();

    List<List<PdfCharacter>> characterLines = groupCharactersIntoLines(sortedCharacters);

    List<PdfTextLine> lines = new ArrayList<>();

    for (List<PdfCharacter> characterLine : characterLines) {
      List<PdfWord> words = buildWords(characterLine);
      List<PdfTextCell> cells = buildCells(words);

      if (!words.isEmpty()) {
        PdfCharacter firstCharacter = characterLine.getFirst();

        lines.add(new PdfTextLine(
            firstCharacter.pageNumber(),
            firstCharacter.y(),
            words,
            cells));
      }
    }

    return lines;
  }

  private List<List<PdfCharacter>> groupCharactersIntoLines(
      List<PdfCharacter> characters) {
    List<List<PdfCharacter>> lines = new ArrayList<>();

    for (PdfCharacter character : characters) {

      if (lines.isEmpty()) {
        List<PdfCharacter> newLine = new ArrayList<>();

        newLine.add(character);
        lines.add(newLine);

        continue;
      }

      List<PdfCharacter> currentLine = lines.getLast();

      PdfCharacter firstCharacterInLine = currentLine.getFirst();

      boolean samePage = character.pageNumber() == firstCharacterInLine.pageNumber();

      boolean sameLine = Math.abs(
          character.y()
              - firstCharacterInLine.y()) <= LINE_Y_TOLERANCE;

      if (samePage && sameLine) {
        currentLine.add(character);
      } else {
        List<PdfCharacter> newLine = new ArrayList<>();

        newLine.add(character);
        lines.add(newLine);
      }
    }

    return lines;
  }

  private List<PdfWord> buildWords(
      List<PdfCharacter> characters) {
    List<PdfCharacter> sortedCharacters = characters.stream()
        .sorted(
            Comparator.comparing(
                PdfCharacter::x))
        .toList();

    List<PdfWord> words = new ArrayList<>();

    if (sortedCharacters.isEmpty()) {
      return words;
    }

    List<PdfCharacter> currentWord = new ArrayList<>();

    currentWord.add(sortedCharacters.getFirst());

    for (int i = 1; i < sortedCharacters.size(); i++) {

      PdfCharacter previous = sortedCharacters.get(i - 1);

      PdfCharacter current = sortedCharacters.get(i);

      float previousEndX = previous.x() + previous.width();

      float gap = current.x() - previousEndX;

      float spaceThreshold = previous.spaceWidth()
          * SPACE_THRESHOLD_FACTOR;

      if (gap > spaceThreshold) {
        words.add(createWord(currentWord));

        currentWord = new ArrayList<>();
      }

      currentWord.add(current);
    }

    if (!currentWord.isEmpty()) {
      words.add(createWord(currentWord));
    }

    return words;
  }

  private PdfWord createWord(
      List<PdfCharacter> characters) {
    PdfCharacter first = characters.getFirst();
    PdfCharacter last = characters.getLast();

    StringBuilder text = new StringBuilder();

    for (PdfCharacter character : characters) {
      text.append(character.text());
    }

    float width = (last.x() + last.width())
        - first.x();

    float height = characters.stream()
        .map(PdfCharacter::height)
        .max(Float::compare)
        .orElse(first.height());

    return new PdfWord(
        first.pageNumber(),
        first.x(),
        first.y(),
        width,
        height,
        first.fontSize(),
        text.toString());
  }

  private List<PdfTextCell> buildCells(
      List<PdfWord> words) {
    List<PdfTextCell> cells = new ArrayList<>();

    if (words.isEmpty()) {
      return cells;
    }

    List<PdfWord> currentCell = new ArrayList<>();
    currentCell.add(words.getFirst());

    for (int i = 1; i < words.size(); i++) {

      PdfWord previous = words.get(i - 1);
      PdfWord current = words.get(i);

      float previousEndX = previous.x() + previous.width();

      float gap = current.x() - previousEndX;

      if (gap > CELL_GAP_THRESHOLD) {
        cells.add(createCell(currentCell));
        currentCell = new ArrayList<>();
      }

      currentCell.add(current);
    }

    if (!currentCell.isEmpty()) {
      cells.add(createCell(currentCell));
    }

    return cells;
  }

  private PdfTextCell createCell(
      List<PdfWord> words) {
    PdfWord first = words.getFirst();
    PdfWord last = words.getLast();

    String text = words.stream()
        .map(PdfWord::text)
        .collect(Collectors.joining(" "));

    float width = (last.x() + last.width())
        - first.x();

    return new PdfTextCell(
        first.x(),
        width,
        text,
        List.copyOf(words));
  }
}
