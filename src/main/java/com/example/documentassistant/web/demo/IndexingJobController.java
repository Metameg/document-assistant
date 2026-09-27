package com.example.documentassistant.web.demo;

import com.example.documentassistant.ingestion.pipeline.CorpusIndexingService;
import com.example.documentassistant.ingestion.pipeline.IndexingJobRegistry;
import com.example.documentassistant.ingestion.pipeline.IndexingJobStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/demo/indexing-jobs")
public class IndexingJobController {

  private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

  private final CorpusIndexingService indexingService;
  private final IndexingJobRegistry jobRegistry;

  public IndexingJobController(
      CorpusIndexingService indexingService,
      IndexingJobRegistry jobRegistry) {

    this.indexingService = indexingService;
    this.jobRegistry = jobRegistry;
  }

  @PostMapping
  public ResponseEntity<IndexingJobStatus> startIndexing() {

    IndexingJobStatus status = indexingService.startIndexing();

    URI location = ServletUriComponentsBuilder
        .fromCurrentRequest()
        .path("/{jobId}")
        .buildAndExpand(status.jobId())
        .toUri();

    return ResponseEntity
        .accepted()
        .location(location)
        .body(status);
  }

  @GetMapping("/{jobId}")
  public IndexingJobStatus getStatus(
      @PathVariable UUID jobId) {

    return jobRegistry.getStatus(jobId);
  }

  @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable UUID jobId) {

    jobRegistry.getStatus(jobId);

    SseEmitter emitter = new SseEmitter(
        SSE_TIMEOUT_MILLIS);

    AtomicReference<Runnable> cleanup = new AtomicReference<>(() -> {
    });

    Consumer<IndexingJobStatus> listener = status -> sendStatus(
        emitter,
        status,
        cleanup);

    Runnable unsubscribe = jobRegistry.addListener(
        jobId,
        listener);

    cleanup.set(unsubscribe);

    emitter.onCompletion(
        () -> cleanup.get().run());

    emitter.onTimeout(() -> {
      cleanup.get().run();
      emitter.complete();
    });

    emitter.onError(
        ignored -> cleanup.get().run());

    sendStatus(
        emitter,
        jobRegistry.getStatus(jobId),
        cleanup);

    return emitter;
  }

  private void sendStatus(
      SseEmitter emitter,
      IndexingJobStatus status,
      AtomicReference<Runnable> cleanup) {

    IndexingProgressEvent event = IndexingProgressEvent.from(status);

    try {
      emitter.send(
          SseEmitter.event()
              .id(
                  Long.toString(
                      event.sequence()))
              .name(event.eventType())
              .data(event));

      if (event.terminal()) {
        cleanup.get().run();
        emitter.complete();
      }

    } catch (
        IOException
        | IllegalStateException exception) {
      cleanup.get().run();

      try {
        emitter.completeWithError(
            exception);
      } catch (RuntimeException ignored) {
        // The connection may already be closed.
      }
    }
  }
}
