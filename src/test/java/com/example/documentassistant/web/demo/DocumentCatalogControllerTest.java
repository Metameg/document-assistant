package com.example.documentassistant.web.demo;

import com.example.documentassistant.document.catalog.CatalogDocument;
import com.example.documentassistant.document.catalog.CatalogPdf;
import com.example.documentassistant.document.catalog.DocumentCatalogService;
import com.example.documentassistant.document.catalog.DocumentNotFoundException;
import com.example.documentassistant.document.model.ProcessedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@WebMvcTest(DocumentCatalogController.class)
@Import(DocumentCatalogExceptionHandler.class)
class DocumentCatalogControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DocumentCatalogService catalogService;

  @Test
  void listsKnownDocuments() throws Exception {
    var revision = new ProcessedDocument.Revision(
        "10000007490-B",
        "B",
        "06/06/18",
        2018);

    when(catalogService.listDocuments())
        .thenReturn(List.of(
            new CatalogDocument(
                "product_1",
                "product_1.pdf",
                4,
                List.of("G007144-0"),
                revision,
                true)));

    mockMvc.perform(
        get("/api/demo/documents"))
        .andExpect(status().isOk())
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$[0].documentId")
                .value("product_1"))
        .andExpect(
            jsonPath("$[0].sourceFile")
                .value("product_1.pdf"))
        .andExpect(
            jsonPath("$[0].pageCount")
                .value(4))
        .andExpect(
            jsonPath("$[0].modelNumbers[0]")
                .value("G007144-0"))
        .andExpect(
            jsonPath("$[0].revision.revision")
                .value("B"))
        .andExpect(
            jsonPath("$[0].pdfAvailable")
                .value(true));
  }

  @Test
  void servesKnownPdfInline() throws Exception {
    byte[] content = "%PDF-1.7 test".getBytes();

    when(catalogService.openPdf("product_1"))
        .thenReturn(
            new CatalogPdf(
                "product_1.pdf",
                new ByteArrayResource(content),
                content.length));

    mockMvc.perform(
        get(
            "/api/demo/documents/"
                + "product_1/pdf"))
        .andExpect(status().isOk())
        .andExpect(
            content().contentType(
                MediaType.APPLICATION_PDF))
        .andExpect(result -> {
          String headerValue = result.getResponse().getHeader(
              HttpHeaders.CONTENT_DISPOSITION);

          assertNotNull(headerValue);

          ContentDisposition disposition = ContentDisposition.parse(headerValue);

          assertEquals(
              "inline",
              disposition.getType());

          assertEquals(
              "product_1.pdf",
              disposition.getFilename());
        })
        .andExpect(
            header().string(
                "X-Content-Type-Options",
                "nosniff"))
        .andExpect(
            content().bytes(content));
  }

  @Test
  void returnsNotFoundForUnknownDocument()
      throws Exception {

    when(catalogService.openPdf("unknown"))
        .thenThrow(
            new DocumentNotFoundException(
                "unknown"));

    mockMvc.perform(
        get(
            "/api/demo/documents/"
                + "unknown/pdf"))
        .andExpect(status().isNotFound())
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.title")
                .value("Document not found"))
        .andExpect(
            jsonPath("$.status")
                .value(404));
  }
}
