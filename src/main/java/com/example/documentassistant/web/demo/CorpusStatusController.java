package com.example.documentassistant.web.demo;

import com.example.documentassistant.document.corpus.CorpusStatus;
import com.example.documentassistant.document.corpus.CorpusStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/corpus")
public class CorpusStatusController {

  private final CorpusStatusService statusService;

  public CorpusStatusController(
      CorpusStatusService statusService) {

    this.statusService = statusService;
  }

  @GetMapping("/status")
  public CorpusStatus getStatus() {
    return statusService.getStatus();
  }
}
