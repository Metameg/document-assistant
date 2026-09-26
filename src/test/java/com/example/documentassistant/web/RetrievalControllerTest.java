package com.example.documentassistant.web;

import com.example.documentassistant.retrieval.ChunkRetrievalService;
import com.example.documentassistant.retrieval.RetrievalRequest;
import com.example.documentassistant.retrieval.RetrievalResponse;
import com.example.documentassistant.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RetrievalController.class)
@Import(RetrievalExceptionHandler.class)
class RetrievalControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChunkRetrievalService retrievalService;

  @Test
  void returnsRetrievedChunksAsJson() throws Exception {
    RetrievalResponse response = new RetrievalResponse(
        "What type of engine does model G007144-0 use?",
        5,
        0.0,
        1,
        List.of(engineResult()));

    when(retrievalService.search(
        any(RetrievalRequest.class)))
        .thenReturn(response);

    mockMvc.perform(
        post("/api/retrieval/search")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "query": "What type of engine does model G007144-0 use?",
                  "topK": 5
                }
                """))
        .andExpect(status().isOk())
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.query")
                .value(
                    "What type of engine does model G007144-0 use?"))
        .andExpect(
            jsonPath("$.topK")
                .value(5))
        .andExpect(
            jsonPath("$.similarityThreshold")
                .value(0.0))
        .andExpect(
            jsonPath("$.resultCount")
                .value(1))
        .andExpect(
            jsonPath("$.results[0].similarityScore")
                .value(0.92))
        .andExpect(
            jsonPath("$.results[0].documentId")
                .value("product_1"))
        .andExpect(
            jsonPath("$.results[0].sourceFile")
                .value("product_1.pdf"))
        .andExpect(
            jsonPath("$.results[0].revision")
                .value("A"))
        .andExpect(
            jsonPath("$.results[0].chunkIndex")
                .value(7))
        .andExpect(
            jsonPath("$.results[0].section")
                .value("Engine"))
        .andExpect(
            jsonPath("$.results[0].modelNumbers[0]")
                .value("G007144-0"))
        .andExpect(
            jsonPath("$.results[0].pageNumbers[0]")
                .value(1))
        .andExpect(
            jsonPath("$.results[0].sourceElementIds[0]")
                .value("engine.type"))
        .andExpect(
            jsonPath("$.results[0].text")
                .value(
                    "Type of Engine: "
                        + "GENERAC G-FORCE 500 SERIES"));

    verify(retrievalService).search(
        new RetrievalRequest(
            "What type of engine does model G007144-0 use?",
            5));
  }

  @Test
  void rejectsBlankQueryBeforeCallingService()
      throws Exception {

    mockMvc.perform(
        post("/api/retrieval/search")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "query": "   ",
                  "topK": 5
                }
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(retrievalService);
  }

  @Test
  void returnsBadRequestWhenTopKExceedsMaximum()
      throws Exception {

    when(retrievalService.search(
        any(RetrievalRequest.class)))
        .thenThrow(new IllegalArgumentException(
            "topK must not exceed configured maximum of 20"));

    mockMvc.perform(
        post("/api/retrieval/search")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "query": "What engine is used?",
                  "topK": 21
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.title")
                .value("Invalid retrieval request"))
        .andExpect(
            jsonPath("$.status")
                .value(400))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "topK must not exceed configured "
                        + "maximum of 20"));
  }

  private RetrievedChunk engineResult() {
    return new RetrievedChunk(
        0.92,
        "product_1",
        "product_1.pdf",
        "10000000123",
        "A",
        "2026-01-01",
        2026,
        7,
        "Engine",
        List.of("G007144-0"),
        List.of(1),
        List.of("engine.type"),
        "Type of Engine: GENERAC G-FORCE 500 SERIES");
  }
}
