package com.example.documentassistant.web.demo;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/demo")
public class EvaluationJobController {
  private final EvaluationJobService service;

  public EvaluationJobController(EvaluationJobService service) {
    this.service = service;
  }

  public record StartRequest(List<Integer> kValues) {}

  @PostMapping("/evaluation-jobs")
  public ResponseEntity<EvaluationJobService.Status> start(@RequestBody(required = false) StartRequest request) {
    EvaluationJobService.Status status = service.start(request == null ? null : request.kValues());
    return ResponseEntity.accepted()
        .location(URI.create("/api/demo/evaluation-jobs/" + status.jobId()))
        .body(status);
  }

  @GetMapping("/evaluation-jobs/current")
  public ResponseEntity<EvaluationJobService.Status> current() {
    EvaluationJobService.Status status = service.current();
    return status == null ? ResponseEntity.noContent().build()
        : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
  }

  @GetMapping("/evaluation-jobs/{jobId}")
  public ResponseEntity<EvaluationJobService.Status> status(@PathVariable UUID jobId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jobId));
  }

  @GetMapping(value = "/evaluation/plots/{name}", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<Resource> plot(@PathVariable String name) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(new FileSystemResource(service.plot(name)));
  }
}
