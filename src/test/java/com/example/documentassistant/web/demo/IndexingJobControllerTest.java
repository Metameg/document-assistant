package com.example.documentassistant.web.demo;

import com.example.documentassistant.ingestion.pipeline.CorpusIndexingService;
import com.example.documentassistant.ingestion.pipeline.IndexingJobAlreadyRunningException;
import com.example.documentassistant.ingestion.pipeline.IndexingJobNotFoundException;
import com.example.documentassistant.ingestion.pipeline.IndexingJobRegistry;
import com.example.documentassistant.ingestion.pipeline.IndexingJobStage;
import com.example.documentassistant.ingestion.pipeline.IndexingJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndexingJobController.class)
class IndexingJobControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private CorpusIndexingService corpusIndexingService;

  @MockitoBean
  private IndexingJobRegistry indexingJobRegistry;

  @Test
  void startsIndexingJob() throws Exception {
    UUID jobId = UUID.randomUUID();

    when(corpusIndexingService.startIndexing())
        .thenReturn(jobStatus(jobId));

    mockMvc.perform(post("/api/demo/indexing-jobs"))
        .andExpect(status().isAccepted())
        .andExpect(header().string(
            "Location",
            "http://localhost/api/demo/indexing-jobs/" + jobId))
        .andExpect(jsonPath("$.jobId").value(jobId.toString()))
        .andExpect(jsonPath("$.stage").value("IDLE"))
        .andExpect(jsonPath("$.terminal").value(false));
  }

  @Test
  void returnsCurrentJobStatus() throws Exception {
    UUID jobId = UUID.randomUUID();

    when(indexingJobRegistry.getStatus(jobId))
        .thenReturn(jobStatus(jobId));

    mockMvc.perform(get("/api/demo/indexing-jobs/{jobId}", jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobId.toString()))
        .andExpect(jsonPath("$.stage").value("IDLE"))
        .andExpect(jsonPath("$.terminal").value(false));
  }

  @Test
  void rejectsSecondConcurrentIndexingJob() throws Exception {
    UUID activeJobId = UUID.randomUUID();

    when(corpusIndexingService.startIndexing())
        .thenThrow(
            new IndexingJobAlreadyRunningException(activeJobId));

    mockMvc.perform(post("/api/demo/indexing-jobs"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.activeJobId")
            .value(activeJobId.toString()));
  }

  @Test
  void returnsNotFoundForUnknownJob() throws Exception {
    UUID jobId = UUID.randomUUID();

    when(indexingJobRegistry.getStatus(jobId))
        .thenThrow(new IndexingJobNotFoundException(jobId));

    mockMvc.perform(get("/api/demo/indexing-jobs/{jobId}", jobId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  private static IndexingJobStatus jobStatus(UUID jobId) {
    Instant createdAt = Instant.parse("2026-09-26T12:00:00Z");

    return new IndexingJobStatus(
        jobId,
        0L,
        IndexingJobStage.IDLE,
        false,
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        createdAt,
        null,
        null);
  }
}
