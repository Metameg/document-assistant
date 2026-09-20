package com.example.documentassistant.document.model;

import java.util.Map;

public record SpecificationRow(
    String name,
    String sharedValue,
    Map<String, String> valuesByModel) {
}
