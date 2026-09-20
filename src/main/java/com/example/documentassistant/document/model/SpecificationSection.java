package com.example.documentassistant.document.model;

import java.util.List;

public record SpecificationSection(
    String title,
    List<SpecificationRow> rows,
    List<TechnicalNote> notes) {
}
