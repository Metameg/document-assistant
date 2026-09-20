package com.example.documentassistant.document.model;

import java.util.List;

public record SpecificationTable(
    List<String> models,
    List<SpecificationSection> sections) {
}
