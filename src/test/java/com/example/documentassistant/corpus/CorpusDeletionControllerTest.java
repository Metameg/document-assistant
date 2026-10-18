package com.example.documentassistant.corpus;

import com.example.documentassistant.document.catalog.DocumentCatalogService;
import com.example.documentassistant.document.corpus.CorpusStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CorpusController.class)
@Import(CorpusExceptionHandler.class)
class CorpusDeletionControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean CorpusRepository repo;
  @MockitoBean CorpusService service;
  @MockitoBean CorpusEvaluationService evaluation;
  @MockitoBean DocumentCatalogService catalog;
  @MockitoBean CorpusStatusService legacyStatus;

  @Test void removesOnlyTheRequestedPaper() throws Exception {
    UUID corpus=UUID.randomUUID(),paper=UUID.randomUUID();
    var view=new CorpusService.View(corpus.toString(),"Papers","research",UUID.randomUUID(),false,2,253);
    when(service.deleteDocument(corpus,paper)).thenReturn(new CorpusService.RemovalResult(view,"paper.pdf",1,1,1,false));
    mvc.perform(delete("/api/corpora/{corpus}/documents/{paper}",corpus,paper)).andExpect(status().isOk())
        .andExpect(jsonPath("$.corpus.documentCount").value(2)).andExpect(jsonPath("$.removedDocument").value("paper.pdf"));
    verify(service).deleteDocument(corpus,paper);verify(service,never()).deleteCorpus(any());
  }
  @Test void deletesTheRequestedCorpus() throws Exception {
    UUID corpus=UUID.randomUUID();when(service.deleteCorpus(corpus)).thenReturn(new CorpusService.DeletedCorpus("Papers",false));
    mvc.perform(delete("/api/corpora/{corpus}",corpus)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Papers"));
    verify(service).deleteCorpus(corpus);
  }
  @Test void reportsActiveJobsWithoutDeletingAnythingElse() throws Exception {
    UUID corpus=UUID.randomUUID();when(service.deleteCorpus(corpus)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"A job is already running for this corpus"));
    mvc.perform(delete("/api/corpora/{corpus}",corpus)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("A job is already running for this corpus"));
    verifyNoInteractions(repo,evaluation,catalog,legacyStatus);
  }
  @Test void rejectsDeletionOfTheBuiltInCorpus() throws Exception {
    mvc.perform(delete("/api/corpora/specifications")).andExpect(status().isBadRequest());
    mvc.perform(delete("/api/corpora/specifications/documents/{paper}",UUID.randomUUID())).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
