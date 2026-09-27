package com.example.documentassistant.web.demo;

import com.example.documentassistant.document.corpus.CorpusStatus;
import com.example.documentassistant.document.corpus.CorpusStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CorpusStatusController.class)
class CorpusStatusControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private CorpusStatusService statusService;

  @Test
  void returnsCurrentCorpusStatus()
      throws Exception {

    Instant checkedAt = Instant.parse(
        "2026-09-26T20:00:00Z");

    when(statusService.getStatus())
        .thenReturn(
            new CorpusStatus(
                true,
                true,
                9,
                9,
                571,
                List.of(),
                List.of(),
                checkedAt));

    mockMvc.perform(
        get("/api/demo/corpus/status"))
        .andExpect(status().isOk())
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.indexed")
                .value(true))
        .andExpect(
            jsonPath("$.inSync")
                .value(true))
        .andExpect(
            jsonPath("$.catalogDocumentCount")
                .value(9))
        .andExpect(
            jsonPath("$.indexedDocumentCount")
                .value(9))
        .andExpect(
            jsonPath("$.storedChunkCount")
                .value(571))
        .andExpect(
            jsonPath("$.missingDocumentIds")
                .isEmpty())
        .andExpect(
            jsonPath("$.unexpectedDocumentIds")
                .isEmpty())
        .andExpect(
            jsonPath("$.checkedAt")
                .value(
                    "2026-09-26T20:00:00Z"));

    verify(statusService).getStatus();
  }
}
