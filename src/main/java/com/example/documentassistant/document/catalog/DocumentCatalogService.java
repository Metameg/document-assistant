package com.example.documentassistant.document.catalog;

import com.example.documentassistant.document.model.ProcessedDocument;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class DocumentCatalogService {

  private final ObjectMapper objectMapper;
  private final DocumentCatalogProperties properties;

  public DocumentCatalogService(
      ObjectMapper objectMapper,
      DocumentCatalogProperties properties) {

    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public List<CatalogDocument> listDocuments() {
    return discoverDocuments()
        .values()
        .stream()
        .map(RegisteredDocument::document)
        .toList();
  }

  public CatalogPdf openPdf(
      String documentId) {

    RegisteredDocument registered = discoverDocuments().get(documentId);

    if (registered == null
        || !registered.document().pdfAvailable()) {
      throw new DocumentNotFoundException(
          documentId);
    }

    try {
      return new CatalogPdf(
          registered.document().sourceFile(),
          new FileSystemResource(
              registered.pdfPath()),
          Files.size(registered.pdfPath()));
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Could not read PDF for document: "
              + documentId,
          exception);
    }
  }

  private Map<String, RegisteredDocument> discoverDocuments() {

    Path processedDirectory = properties.processedDirectory()
        .toAbsolutePath()
        .normalize();

    Path rawPdfDirectory = properties.rawPdfDirectory()
        .toAbsolutePath()
        .normalize();

    if (!Files.isDirectory(processedDirectory)) {
      throw new IllegalStateException(
          "Processed document directory "
              + "does not exist: "
              + processedDirectory);
    }

    List<Path> jsonFiles;

    try (var paths = Files.list(processedDirectory)) {

      jsonFiles = paths
          .filter(Files::isRegularFile)
          .filter(this::hasJsonExtension)
          .sorted()
          .toList();

    } catch (IOException exception) {
      throw new IllegalStateException(
          "Could not inspect processed "
              + "document directory: "
              + processedDirectory,
          exception);
    }

    Map<String, RegisteredDocument> documents = new TreeMap<>();

    for (Path jsonFile : jsonFiles) {
      ProcessedDocument processedDocument = readProcessedDocument(jsonFile);

      validateDocumentId(
          processedDocument.documentId(),
          jsonFile);

      Path pdfPath = resolvePdfPath(
          rawPdfDirectory,
          processedDocument.sourceFile(),
          jsonFile);

      boolean pdfAvailable = Files.isRegularFile(pdfPath)
          && Files.isReadable(pdfPath);

      CatalogDocument catalogDocument = new CatalogDocument(
          processedDocument.documentId(),
          processedDocument.sourceFile(),
          processedDocument.pageCount(),
          processedDocument.models(),
          processedDocument.revision(),
          pdfAvailable);

      RegisteredDocument previous = documents.putIfAbsent(
          processedDocument.documentId(),
          new RegisteredDocument(
              catalogDocument,
              pdfPath));

      if (previous != null) {
        throw new IllegalStateException(
            "Duplicate documentId in "
                + "processed document directory: "
                + processedDocument.documentId());
      }
    }

    return Map.copyOf(documents);
  }

  private ProcessedDocument readProcessedDocument(
      Path jsonFile) {

    try {
      return objectMapper.readValue(
          jsonFile.toFile(),
          ProcessedDocument.class);

    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Could not read processed document: "
              + jsonFile,
          exception);
    }
  }

  private Path resolvePdfPath(
      Path rawPdfDirectory,
      String sourceFile,
      Path jsonFile) {

    if (sourceFile == null
        || sourceFile.isBlank()) {
      throw new IllegalStateException(
          "Processed document has no "
              + "sourceFile: "
              + jsonFile);
    }

    if (sourceFile.contains("/")
        || sourceFile.contains("\\")) {
      throw new IllegalStateException(
          "sourceFile must contain only "
              + "a filename: "
              + sourceFile);
    }

    Path relative = Path.of(sourceFile);

    if (relative.isAbsolute()
        || relative.getNameCount() != 1
        || !sourceFile.equals(
            relative.getFileName().toString())) {
      throw new IllegalStateException(
          "Unsafe sourceFile: "
              + sourceFile);
    }

    if (!sourceFile
        .toLowerCase(Locale.ROOT)
        .endsWith(".pdf")) {
      throw new IllegalStateException(
          "sourceFile is not a PDF: "
              + sourceFile);
    }

    Path resolved = rawPdfDirectory
        .resolve(relative)
        .normalize();

    if (!resolved.startsWith(rawPdfDirectory)) {
      throw new IllegalStateException(
          "PDF path escapes configured "
              + "raw document directory: "
              + sourceFile);
    }

    return resolved;
  }

  private void validateDocumentId(
      String documentId,
      Path jsonFile) {

    if (documentId == null
        || !documentId.matches(
            "[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalStateException(
          "Invalid documentId in "
              + jsonFile);
    }
  }

  private boolean hasJsonExtension(
      Path path) {

    return path
        .getFileName()
        .toString()
        .toLowerCase(Locale.ROOT)
        .endsWith(".json");
  }

  private record RegisteredDocument(
      CatalogDocument document,
      Path pdfPath) {
  }
}
