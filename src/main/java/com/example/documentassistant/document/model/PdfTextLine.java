package com.example.documentassistant.document.model;

import java.util.List;

public record PdfTextLine(
    int pageNumber,
    float y,
    List<PdfWord> words,
    List<PdfTextCell> cells) {
}
