package com.example.documentassistant.document.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentCatalogServiceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void listsDocumentsAndOpensKnownPdf()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve("processed"));

    Path rawPdfDirectory = Files.createDirectory(
        temporaryDirectory.resolve("raw"));

    Files.writeString(
        processedDirectory.resolve(
            "product_1.json"),
        processedDocument(
            "product_1",
            "product_1.pdf"));

    byte[] pdfBytes = "%PDF-1.7 test".getBytes();

    Files.write(
        rawPdfDirectory.resolve(
            "product_1.pdf"),
        pdfBytes);

    DocumentCatalogService service = service(
        processedDirectory,
        rawPdfDirectory);

    List<CatalogDocument> documents = service.listDocuments();

    assertEquals(1, documents.size());

    CatalogDocument document = documents.getFirst();

    assertEquals(
        "product_1",
        document.documentId());

    assertEquals(
        "product_1.pdf",
        document.sourceFile());

    assertEquals(
        4,
        document.pageCount());

    assertEquals(
        List.of("G007144-0"),
        document.modelNumbers());

    assertTrue(
        document.pdfAvailable());

    CatalogPdf pdf = service.openPdf("product_1");

    assertEquals(
        "product_1.pdf",
        pdf.sourceFile());

    assertEquals(
        pdfBytes.length,
        pdf.contentLength());

    assertArrayEquals(
        pdfBytes,
        pdf.resource()
            .getInputStream()
            .readAllBytes());
  }

  @Test
  void listsDocumentWhenPdfIsMissing()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve("processed"));

    Path rawPdfDirectory = Files.createDirectory(
        temporaryDirectory.resolve("raw"));

    Files.writeString(
        processedDirectory.resolve(
            "product_1.json"),
        processedDocument(
            "product_1",
            "product_1.pdf"));

    DocumentCatalogService service = service(
        processedDirectory,
        rawPdfDirectory);

    CatalogDocument document = service.listDocuments().getFirst();

    assertFalse(
        document.pdfAvailable());

    assertThrows(
        DocumentNotFoundException.class,
        () -> service.openPdf("product_1"));
  }

  @Test
  void rejectsSourceFilenameWithParentTraversal()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve("processed"));

    Path rawPdfDirectory = Files.createDirectory(
        temporaryDirectory.resolve("raw"));

    Files.writeString(
        processedDirectory.resolve(
            "product_1.json"),
        processedDocument(
            "product_1",
            "../secret.pdf"));

    DocumentCatalogService service = service(
        processedDirectory,
        rawPdfDirectory);

    var exception = assertThrows(
        IllegalStateException.class,
        service::listDocuments);

    assertTrue(
        exception.getMessage().contains(
            "sourceFile must contain only "
                + "a filename"));
  }

  @Test
  void rejectsUnknownDocumentId()
      throws Exception {

    Path processedDirectory = Files.createDirectory(
        temporaryDirectory.resolve("processed"));

    Path rawPdfDirectory = Files.createDirectory(
        temporaryDirectory.resolve("raw"));

    Files.writeString(
        processedDirectory.resolve(
            "product_1.json"),
        processedDocument(
            "product_1",
            "product_1.pdf"));

    DocumentCatalogService service = service(
        processedDirectory,
        rawPdfDirectory);

    assertThrows(
        DocumentNotFoundException.class,
        () -> service.openPdf(
            "not-a-known-document"));
  }

  private DocumentCatalogService service(
      Path processedDirectory,
      Path rawPdfDirectory) {

    return new DocumentCatalogService(
        new ObjectMapper(),
        new DocumentCatalogProperties(
            processedDirectory,
            rawPdfDirectory));
  }

  private String processedDocument(
      String documentId,
      String sourceFile) {

    return """
        {
          "schemaVersion": "1.3",
          "documentId": "%s",
          "sourceFile": "%s",
          "documentType": "generator_spec_sheet",
          "pageCount": 4,
          "models": ["G007144-0"],
          "revision": {
            "partNumber": "10000007490-B",
            "revision": "B",
            "publicationDate": "06/06/18",
            "copyrightYear": 2018
          },
          "sections": [],
          "extraction": null
        }
        """.formatted(
        documentId,
        sourceFile);
  }
}
