
package com.example.documentassistant.document.model;

public record PdfWord(
    int pageNumber,
    float x,
    float y,
    float width,
    float height,
    float fontSize,
    String text) {
}
