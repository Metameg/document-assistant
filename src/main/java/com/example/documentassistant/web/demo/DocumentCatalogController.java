package com.example.documentassistant.web.demo;

import com.example.documentassistant.document.catalog.CatalogDocument;
import com.example.documentassistant.document.catalog.CatalogPdf;
import com.example.documentassistant.document.catalog.DocumentCatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/demo/documents")
public class DocumentCatalogController {

  private final DocumentCatalogService catalogService;

  public DocumentCatalogController(
      DocumentCatalogService catalogService) {

    this.catalogService = catalogService;
  }

  @GetMapping
  public List<CatalogDocument> listDocuments() {
    return catalogService.listDocuments();
  }

  @GetMapping(value = "/{documentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<Resource> openPdf(
      @PathVariable String documentId) {

    CatalogPdf pdf = catalogService.openPdf(documentId);

    ContentDisposition disposition = ContentDisposition
        .inline()
        .filename(
            pdf.sourceFile(),
            StandardCharsets.UTF_8)
        .build();

    return ResponseEntity
        .ok()
        .contentType(
            MediaType.APPLICATION_PDF)
        .contentLength(
            pdf.contentLength())
        .cacheControl(
            CacheControl.noCache())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            disposition.toString())
        .header(
            "X-Content-Type-Options",
            "nosniff")
        .body(pdf.resource());
  }
}
