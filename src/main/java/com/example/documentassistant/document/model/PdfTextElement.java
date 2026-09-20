package com.example.documentassistant.document.model;

public record PdfTextElement(
    int pageNumber,
    float x,
    float y,
    float width,
    float height,
    float fontSize,
    String text) {
}
