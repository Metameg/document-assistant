package com.example.documentassistant.document.model;

public record PdfCharacter(
    int pageNumber,
    float x,
    float y,
    float width,
    float height,
    float fontSize,
    float spaceWidth,
    String text) {
}
