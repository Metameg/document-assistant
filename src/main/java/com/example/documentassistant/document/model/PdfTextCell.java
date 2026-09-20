package com.example.documentassistant.document.model;

import java.util.List;

public record PdfTextCell(
    float x,
    float width,
    String text,
    List<PdfWord> words) {
}
